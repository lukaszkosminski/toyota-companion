package pl.lk.toyotacompanion.core.sync

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.lk.toyotacompanion.core.data.ArchiveReading
import pl.lk.toyotacompanion.core.data.ArchiveRefuel
import pl.lk.toyotacompanion.core.data.ArchiveSnapshot
import pl.lk.toyotacompanion.core.data.CostRepository
import pl.lk.toyotacompanion.core.data.FuelReadingRepository
import pl.lk.toyotacompanion.core.data.RefuelRepository
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.SyncConfig
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.SyncStateRepository
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.TripRepository
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.data.VehicleRepository
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.MANUAL_READING_MAX_AGE
import pl.lk.toyotacompanion.core.domain.findNearbyRefuel
import pl.lk.toyotacompanion.core.domain.levelBeforeManualRefuel
import pl.lk.toyotacompanion.core.domain.manualRefuel
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.TripCost
import pl.lk.toyotacompanion.core.domain.confirmRefuel as domainConfirmRefuel
import pl.lk.toyotacompanion.core.domain.detectRefuel
import pl.lk.toyotacompanion.core.domain.dismissRefuel as domainDismissRefuel
import pl.lk.toyotacompanion.core.domain.fuelEconomyIntervals
import pl.lk.toyotacompanion.core.domain.recomputeCosts
import pl.lk.toyotacompanion.core.domain.refuelForecast
import pl.lk.toyotacompanion.core.domain.RefuelForecast
import pl.lk.toyotacompanion.core.domain.stationSuggestions as domainStationSuggestions
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException
import pl.lk.toyotacompanion.core.toyota.ToyotaDataException
import pl.lk.toyotacompanion.core.toyota.ToyotaLoginException
import pl.lk.toyotacompanion.core.toyota.ToyotaNetworkException
import pl.lk.toyotacompanion.core.toyota.ToyotaSecrets

/**
 * Synchronization of one vehicle: vehicles -> telemetry -> refuel detection ->
 * trips -> costs (docs/SPEC.md §8).
 *
 * Pure Kotlin: repositories and the Toyota gateway are interfaces, the clock is
 * injected, so this class runs in JVM tests without Android or network. The
 * app module drives it from WorkManager.
 *
 * Guarantees (docs/ARCHITECTURE.md failure table):
 * - a failure never removes or rewinds stored data, it only records the error
 *   and leaves [pl.lk.toyotacompanion.core.data.SyncState.isStale] true;
 * - the trip watermark advances only after a fully successful fetch;
 * - re-running the same synchronization adds nothing (deduplication by id).
 */
class SyncEngine(
    private val gateway: ToyotaGateway,
    private val vehicles: VehicleRepository,
    private val trips: TripRepository,
    private val refuels: RefuelRepository,
    private val readings: FuelReadingRepository,
    private val costs: CostRepository,
    private val state: SyncStateRepository,
    private val config: SyncConfig = SyncConfig(),
    /**
     * Price per liter used for trips that have no confirmed refuel before them
     * (docs/SPEC.md §12.1). Read on every recompute, so changing the user's setting
     * works without a restart and a background synchronization sees the current
     * value. Defaults to [SyncConfig.defaultPrice] — for tests and for using `core`
     * outside this app; the app injects its own settings store.
     */
    private val defaultPriceProvider: suspend () -> BigDecimal? = { config.defaultPrice },
    private val clock: () -> Instant = { Instant.now() },
    private val hasCredentials: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()

    /**
     * One shared snapshot of [trips] (C22 Q6): after a synchronization the widget, the forecast, the consumption
     * card, the weather and every open screen read the same list, so it is built once instead of once per reader.
     * [tripsVersion] grows before and after every write of trips or costs ([writingTrips]); a snapshot is kept only
     * when no write happened while it was read, so a reader never keeps a list from before a write. The repositories
     * are written only by this engine (the app wires no other writer), which is what makes the snapshot safe.
     */
    private class TripsSnapshot(val vin: String, val version: Long, val trips: List<StoredTrip>)

    private val tripsVersion = java.util.concurrent.atomic.AtomicLong()

    @Volatile
    private var tripsSnapshot: TripsSnapshot? = null
    private val tripsReadLock = Mutex()

    /** Runs a write of trips or costs; the snapshot is dropped before it starts and after it ends, also on failure. */
    private suspend fun <T> writingTrips(write: suspend () -> T): T {
        tripsVersion.incrementAndGet()
        tripsSnapshot = null
        try {
            return write()
        } finally {
            tripsVersion.incrementAndGet()
            tripsSnapshot = null
        }
    }

    /** Full pipeline. [SyncOutcome.Skipped] when another run holds the single flight. */
    suspend fun fullSync(): SyncOutcome = guarded { syncNow() }

    /** Cost recount only, no network — call after the user confirms a price (PRD F4). */
    suspend fun recomputeCosts(): SyncOutcome = guarded { recomputeOnly() }

    /**
     * Cost recount on request of the default-price screen (docs/SPEC.md §12.1). No
     * network and no [SyncState] change: this is not a synchronization, so it must
     * not rewrite the status of the last one (docs/SPEC.md §8.5).
     *
     * Like [confirmRefuel], this waits for the mutex ([kotlinx.coroutines.sync.Mutex.withLock])
     * instead of trying it, so a recount started during a running synchronization
     * queues behind it instead of being skipped.
     */
    suspend fun recomputeCostsNow(): CostRecompute =
        mutex.withLock {
            val vin = state.get().vin ?: return@withLock CostRecompute.NoVehicle
            CostRecompute.Done(recomputeAndSave(vin))
        }

    /** Which vehicle to synchronize when the account has several (PRD §3). */
    suspend fun selectVehicle(vin: String) {
        writingTrips { state.save(state.get().copy(vin = vin)) }
    }

    /** The vehicle every read is about; null before the first synchronization (docs/SPEC.md §42-§44). */
    suspend fun selectedVin(): String? = state.get().vin

    /** What the UI shows: local data plus “current / not current”. */
    suspend fun status(): SyncStatus {
        val current = state.get()
        val latest = current.vin?.let { readings.latest(it) }
        return SyncStatus(
            state = current,
            tripsStored = trips.count(),
            readingsStored = readings.count(),
            // Without a vehicle the list of waiting refuels is empty ([pendingRefuels]), so the counter is zero too.
            pendingRefuels = current.vin?.let { refuels.pending(it).size } ?: 0,
            // One extra row: the odometer of the newest reading (docs/SPEC.md §9.3).
            odometerKm = latest?.odometerKm,
            // The moment Toyota saw the car, not when the phone fetched it (docs/SPEC.md §47).
            carStateAt = latest?.ts,
        )
    }

    /**
     * Trips of the selected vehicle with their stored cost, newest start first
     * (docs/SPEC.md §10.1). A read like [status]: no network, no gateway call and
     * no synchronization mutex — so a list opened while a synchronization runs
     * may not yet show the costs recomputed in its last step (docs/SPEC.md §8.3).
     *
     * Every cost comes from a single [CostRepository.all] read (docs/SPEC.md §10.1):
     * the archive holds hundreds of trips and one query per trip is unacceptable.
     * Without a selected VIN the archive of other vehicles stays hidden.
     */
    suspend fun trips(): List<StoredTrip> {
        val vin = state.get().vin ?: return emptyList()
        currentSnapshot(vin)?.let { return it }
        // Readers that arrive together wait for one read instead of each reading the whole archive.
        return tripsReadLock.withLock {
            currentSnapshot(vin)?.let { return@withLock it }
            val version = tripsVersion.get()
            val costByTrip: Map<String, TripCost> = costs.all().associateBy { it.tripId }
            val read = trips.all(vin).asReversed().map { it.withCost(costByTrip[it.id]) }
            if (tripsVersion.get() == version) tripsSnapshot = TripsSnapshot(vin, version, read)
            read
        }
    }

    private fun currentSnapshot(vin: String): List<StoredTrip>? =
        tripsSnapshot?.takeIf { it.vin == vin && it.version == tripsVersion.get() }?.trips

    /**
     * Refuels of the selected vehicle that wait for a price, newest first
     * (docs/SPEC.md §11.1). A read like [status]: no network, no gateway call and
     * no synchronization mutex, so opening the list never waits for a running
     * synchronization.
     */
    suspend fun pendingRefuels(): List<Refuel> {
        val vin = state.get().vin ?: return emptyList()
        return refuels.pending(vin)
    }

    /**
     * Confirms one pending refuel (PRD F2) and recounts the costs of the vehicle
     * (PRD F4, docs/SPEC.md §11.1). Only the domain [domainConfirmRefuel] validates
     * the values and derives the missing one; the caller never validates anything.
     * [litersSource] is the one record the UI can hand over: `ESTIMATE` only after
     * the user has plainly accepted the sensor estimate (PRD F2a, docs/SPEC.md §2a),
     * `null` otherwise, so the domain still decides between `RECEIPT` and `DERIVED`
     * and rejects a source without liters with [RefuelChange.Invalid].
     *
     * Unlike [fullSync], this waits for the mutex ([withLock]) instead of trying
     * it: a price typed while a synchronization runs has to be stored, not skipped.
     */
    suspend fun confirmRefuel(
        id: String,
        pricePerLiter: BigDecimal? = null,
        liters: BigDecimal? = null,
        totalCost: BigDecimal? = null,
        filledToFull: Boolean? = null,
        litersSource: LitersSource? = null,
        station: String? = null,
    ): RefuelChange = editRefuel(id) { stored ->
        domainConfirmRefuel(
            stored,
            pricePerLiter = pricePerLiter,
            liters = liters,
            totalCost = totalCost,
            filledToFull = filledToFull,
            litersSource = litersSource,
            station = station,
        )
    }

    /**
     * Adds a refuel the owner read off a receipt, when the sensor did not detect it (C45, docs/SPEC.md §15.4).
     * [moment] is not in the future. The starting level and the odometer come from the last fuel reading before
     * [moment], corrected by the trips since it; a stored refuel within 12 hours is refused ([RefuelChange.Duplicate]).
     * Written under the mutex, then the costs are recounted.
     */
    suspend fun addManualRefuel(
        moment: Instant,
        liters: BigDecimal,
        pricePerLiter: BigDecimal? = null,
        totalCost: BigDecimal? = null,
        filledToFull: Boolean? = null,
        station: String? = null,
    ): RefuelChange = mutex.withLock {
        val vin = state.get().vin ?: return@withLock RefuelChange.NoVehicle
        if (moment.isAfter(clock())) return@withLock RefuelChange.Invalid
        val stored = refuels.all(vin).filter { it.status != RefuelStatus.DISMISSED }
        findNearbyRefuel(stored, moment)?.let { return@withLock RefuelChange.Duplicate(it) }
        val reading = readings.since(moment.minus(MANUAL_READING_MAX_AGE), vin).lastOrNull { !it.ts.isAfter(moment) }
            ?: return@withLock RefuelChange.NoReading
        val tripsBefore = trips.all(vin).map { it.toTrip() }
        val levelBefore = levelBeforeManualRefuel(reading, tripsBefore, moment, config.tankCapacityL)
            ?: return@withLock RefuelChange.NoReading
        val refuel = try {
            manualRefuel(
                id = "manual-" + UUID.randomUUID(),
                ts = moment,
                odometerKm = reading.odometerKm,
                levelBefore = levelBefore,
                liters = liters,
                pricePerLiter = pricePerLiter,
                totalCost = totalCost,
                filledToFull = filledToFull,
                station = station,
                capacityL = config.tankCapacityL,
            )
        } catch (e: IllegalArgumentException) {
            return@withLock RefuelChange.Invalid
        }
        refuels.addIfAbsent(refuel, vin)
        RefuelChange.Done(refuel, recomputeAndSave(vin))
    }

    /** Discards one pending refuel (docs/SPEC.md §3) and recounts the costs. */
    suspend fun dismissRefuel(id: String): RefuelChange = editRefuel(id) { domainDismissRefuel(it) }

    /**
     * Corrects the liters of a **confirmed** refuel with the receipt (PRD F9, docs/SPEC.md
     * §15.3): the liters become `RECEIPT` and [filledToFull] is set. With [totalCost] the
     * domain derives the real price from the receipt (liters + total); without it the
     * stored price stays and the total is price × liters. Same write path as a price:
     * under the mutex, costs recomputed (the liters change the tank price, §6).
     * `NotConfirmed` for a pending or dismissed entry; the domain alone validates.
     */
    suspend fun amendRefuel(
        id: String,
        liters: BigDecimal,
        totalCost: BigDecimal? = null,
        filledToFull: Boolean,
        station: String? = null,
    ): RefuelChange = editRefuel(
        id,
        required = RefuelStatus.CONFIRMED,
        // A confirmed entry is never in pending(vin); all(vin) proves it is of this car.
        belongsToVehicle = { vin -> refuels.all(vin).any { it.id == id } },
    ) { stored ->
        domainConfirmRefuel(
            stored,
            pricePerLiter = if (totalCost == null) stored.pricePerLiter else null,
            liters = liters,
            totalCost = totalCost,
            litersSource = LitersSource.RECEIPT,
            filledToFull = filledToFull,
            station = station,
        )
    }

    /**
     * The one write path of both edits. The write and the recount run under the
     * same [mutex] as the synchronization, so no other run can see a confirmed
     * refuel whose trip costs were not recomputed yet (docs/SPEC.md §11.1). A
     * rejection by the domain leaves the archive untouched, and
     * `CancellationException` is never caught here.
     */
    private suspend fun editRefuel(
        id: String,
        required: RefuelStatus = RefuelStatus.PENDING,
        belongsToVehicle: suspend (vin: String) -> Boolean = { vin -> refuels.pending(vin).any { it.id == id } },
        edit: (Refuel) -> Refuel,
    ): RefuelChange =
        mutex.withLock {
            val vin = state.get().vin ?: return@withLock RefuelChange.NoVehicle
            val stored = refuels.byId(id) ?: return@withLock RefuelChange.NotFound
            if (stored.status != required) {
                return@withLock if (required == RefuelStatus.PENDING) RefuelChange.NotPending else RefuelChange.NotConfirmed
            }
            // An entry of another vehicle is invisible to this screen, so it counts as missing.
            if (!belongsToVehicle(vin)) return@withLock RefuelChange.NotFound
            val edited = try {
                edit(stored)
            } catch (e: IllegalArgumentException) {
                return@withLock RefuelChange.Invalid
            }
            refuels.update(edited)
            RefuelChange.Done(edited, recomputeAndSave(vin))
        }

    /**
     * When the selected vehicle reaches its fuel reserve (PRD F14, docs/SPEC.md §20): the
     * latest fuel reading and the trips, computed by the domain. A read like [trips]: no
     * network, no `refresh_status()`, no mutex. `null` without a vehicle or without data.
     */
    suspend fun refuelForecast(now: Instant = clock()): RefuelForecast? {
        val vin = state.get().vin ?: return null
        return refuelForecast(readings.latest(vin), trips.all(vin).map { it.toTrip() }, now, config.tankCapacityL)
    }

    /**
     * Confirmed refuels and the "computer vs actual" fuel economy of the selected
     * vehicle (PRD F9, docs/SPEC.md §15). A read like [trips]: no network, no
     * gateway call and no synchronization mutex; one read of the refuels and one
     * of the trips, the intervals computed by the domain. Without a selected VIN
     * the log is empty — the domain knows no VIN, so it must only ever see one car.
     */
    /** Stored fuel-level readings of the selected vehicle, oldest first; empty without a vehicle (the level chart of the refuel log). */
    suspend fun fuelLevels(): List<pl.lk.toyotacompanion.core.domain.FuelReading> {
        val vin = state.get().vin ?: return emptyList()
        return readings.since(java.time.Instant.EPOCH, vin)
    }

    suspend fun refuelLog(): RefuelLog {
        val vin = state.get().vin ?: return RefuelLog.EMPTY
        val all = refuels.all(vin)
        val tripList = trips.all(vin).map { it.toTrip() }
        val confirmed = confirmedOnly(all)
            .sortedWith(compareByDescending<Refuel> { it.ts }.thenBy { it.id })
        return RefuelLog(confirmed, fuelEconomyIntervals(all, tripList))
    }

    /**
     * The confirmed refuels of the selected vehicle, oldest first (ts, then id) — the refuel log without the
     * economy intervals, for the CSV export (PRD F47, docs/SPEC.md §48). A pending refuel has no price yet, so
     * it stays out; no network, no mutex.
     */
    suspend fun confirmedRefuels(): List<Refuel> {
        val vin = state.get().vin ?: return emptyList()
        return confirmedOnly(refuels.all(vin)).sortedWith(compareBy({ it.ts }, { it.id }))
    }

    /**
     * Station names the owner already used in this vehicle, most often first, then the
     * most recent (PRD F57, docs/SPEC.md §57). A plain read like [refuelLog]: no network,
     * no mutex, no new stored data.
     */
    suspend fun stationSuggestions(): List<String> {
        val vin = state.get().vin ?: return emptyList()
        return domainStationSuggestions(confirmedOnly(refuels.all(vin)))
    }

    /** The one filter both reads share, so the log and the CSV can never disagree on what a "confirmed" refuel is. */
    private fun confirmedOnly(all: List<Refuel>): List<Refuel> = all.filter { it.status == RefuelStatus.CONFIRMED }

    /**
     * The whole archive for the backup file (PRD F7, docs/SPEC.md §14): every
     * vehicle and, for each of them, its trips, refuels and fuel readings. No costs
     * (they are recomputed), no [SyncState] and no secrets. Waits for a running
     * synchronization ([kotlinx.coroutines.sync.Mutex.withLock]) so the copy is
     * consistent; no network.
     */
    suspend fun exportArchive(): ArchiveSnapshot =
        mutex.withLock {
            val fleet = vehicles.all()
            ArchiveSnapshot(
                exportedAt = clock(),
                vehicles = fleet,
                trips = fleet.flatMap { trips.all(it.vin) },
                refuels = fleet.flatMap { vehicle -> refuels.all(vehicle.vin).map { ArchiveRefuel(vehicle.vin, it) } },
                // Readings are never older than the epoch; `since` with it reads them all.
                readings = fleet.flatMap { vehicle ->
                    readings.since(Instant.EPOCH, vehicle.vin).map { ArchiveReading(vehicle.vin, it) }
                },
            )
        }

    /**
     * Adds a decoded backup to the archive (PRD F7, docs/SPEC.md §14). Rows already
     * on the phone stay as they are, except a refuel still waiting for a price
     * here that the file has priced or refused. Costs of the selected vehicle are
     * recomputed afterwards. Under the same mutex as a synchronization, waiting
     * for it like [confirmRefuel]; the collision rule belongs to this layer, not to
     * the domain, so it has no Python mirror.
     */
    suspend fun importArchive(snapshot: ArchiveSnapshot): ArchiveImport =
        mutex.withLock {
            var vehiclesAdded = 0
            for (vehicle in snapshot.vehicles) {
                if (vehicles.byVin(vehicle.vin) == null) {
                    vehicles.upsert(vehicle)
                    vehiclesAdded++
                }
            }

            var tripsAdded = 0
            for ((vin, records) in snapshot.trips.groupBy { it.vin }) {
                val known = trips.all(vin).mapTo(HashSet()) { it.id }
                val fresh = records.filter { it.id !in known }.distinctBy { it.id }
                if (fresh.isNotEmpty()) tripsAdded += writingTrips { trips.upsertAll(fresh) }
            }

            var refuelsAdded = 0
            var refuelsUpdated = 0
            var refuelsSkipped = 0
            for (entry in snapshot.refuels) {
                val incoming = entry.refuel
                val stored = refuels.byId(incoming.id)
                when {
                    stored == null -> if (refuels.addIfAbsent(incoming, entry.vin)) refuelsAdded++
                    stored.status == RefuelStatus.PENDING && incoming.status != RefuelStatus.PENDING -> {
                        refuels.update(incoming)
                        refuelsUpdated++
                    }
                    stored.status == RefuelStatus.DISMISSED && incoming.status == RefuelStatus.CONFIRMED ->
                        refuelsSkipped++
                    else -> Unit
                }
            }

            var readingsAdded = 0
            for (entry in snapshot.readings) {
                if (readings.addIfAbsent(entry.reading, entry.vin)) readingsAdded++
            }

            val vin = state.get().vin
            val tripsCosted = if (vin != null) recomputeAndSave(vin) else 0
            ArchiveImport.Done(
                ArchiveImportCounts(
                    vehiclesAdded = vehiclesAdded,
                    tripsAdded = tripsAdded,
                    refuelsAdded = refuelsAdded,
                    refuelsUpdated = refuelsUpdated,
                    refuelsSkipped = refuelsSkipped,
                    readingsAdded = readingsAdded,
                    // Without a selected vehicle there is no "other" one to point at.
                    otherVehicleTrips = if (vin == null) 0 else snapshot.trips.count { it.vin != vin },
                    tripsCosted = tripsCosted,
                ),
            )
        }

    // ---------------- steps ----------------

    private suspend fun syncNow(): SyncOutcome {
        val startedAt = clock()
        if (!hasCredentials()) {
            return failAt(startedAt, SyncError(SyncErrorKind.NO_CREDENTIALS, "No stored Toyota credentials"))
        }

        val stored = state.get()
        var progress = stored.copy(lastAttemptAt = startedAt)
        state.save(progress)

        // 1. Vehicles. Also detects a VIN change; nothing is ever deleted here.
        val fleet: List<VehicleRecord> = try {
            gateway.vehicles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failAt(startedAt, classify(e))
        }
        fleet.forEach { vehicles.upsert(it) }
        if (fleet.isEmpty()) {
            return failAt(startedAt, SyncError(SyncErrorKind.NO_VEHICLE, "Toyota account returned no vehicle"))
        }
        // A successful step keeps its own time even if a later step fails (docs/SPEC.md §47).
        progress = progress.copy(lastVehiclesSuccessAt = clock())
        state.save(progress)
        val vin: String = when {
            stored.vin != null && fleet.any { it.vin == stored.vin } -> requireNotNull(stored.vin)
            fleet.size == 1 -> fleet.single().vin
            else -> return failAt(
                startedAt,
                SyncError(SyncErrorKind.NO_VEHICLE, "Account lists ${fleet.size} vehicles and none is selected"),
            )
        }

        // 2. Telemetry and refuel detection. A failure here must not stop the trips.
        var readingsAdded = 0
        var refuelsDetected = 0
        var telemetryFailed = false
        // Only the request may fail softly. The writes below are not caught here: a
        // broken archive is a STORAGE failure, never "telemetry unavailable", or a
        // detected refuel could be lost behind an Ok (docs/SPEC.md §8.4).
        val reading = try {
            gateway.telemetry(vin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (reading == null) {
            telemetryFailed = true
        } else {
            val previous = readings.latest(vin)
            if (previous == null) {
                // First reading ever: nothing to compare against (docs/SPEC.md §8.3).
                if (readings.addIfAbsent(reading, vin)) readingsAdded = 1
            } else if (reading.ts.isAfter(previous.ts)) {
                if (readings.addIfAbsent(reading, vin)) readingsAdded = 1
                val detected: Refuel? = detectRefuel(previous, reading, config.minRefuelJumpPct)
                if (detected != null && refuels.addIfAbsent(detected, vin)) refuelsDetected = 1
            }
            // Not newer than the stored reading (Toyota caches telemetry while the
            // car sleeps): no new row and no detection, otherwise every 30 minutes
            // would add a row for the same moment.
            // Success means "fetched and stored" (docs/SPEC.md §47), so the time is written after the archive
            // writes, like trips: a STORAGE failure must not look like a successful telemetry step.
            progress = progress.copy(lastTelemetrySuccessAt = clock())
            state.save(progress)
        }

        // 3. Trips since the watermark, with an overlap for late-arriving trips.
        // `atZone().toLocalDate()` instead of `LocalDate.ofInstant`: the latter is API 34 on Android and the
        // app runs on API 29+, so it would be a NoSuchMethodError there (docs/SPEC.md §8).
        val today = clock().atZone(ZoneOffset.UTC).toLocalDate()
        val from = stored.watermarkEpochDay
            ?.let { LocalDate.ofEpochDay(it).minusDays(config.tripBookmarkOverlapDays) }
            ?: today.minusDays(config.firstSyncWindowDays.toLong())
        val fetched: List<TripRecord> = try {
            gateway.trips(vin, from, today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failAt(startedAt, classify(e))
        }
        val tripsAdded = writingTrips { trips.upsertAll(fetched) }
        progress = progress.copy(lastTripsSuccessAt = clock())
        state.save(progress)

        // 4. Costs (docs/SPEC.md §4, §5).
        val tripsCosted: Int = try {
            recomputeAndSave(vin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // The domain refused the data (docs/SPEC.md §4); anything else here is the
            // archive and is left to [guarded] as STORAGE.
            return failAt(startedAt, classify(e))
        }

        val done = progress.copy(
            vin = vin,
            watermarkEpochDay = today.toEpochDay(),
            lastAttemptAt = startedAt,
            lastSuccessAt = clock(),
            lastErrorAt = null,
            lastErrorKind = null,
            lastErrorMessage = null,
            lastErrorStatus = null,
        )
        state.save(done)
        return SyncOutcome.Ok(tripsAdded, readingsAdded, refuelsDetected, tripsCosted, telemetryFailed, done)
    }

    private suspend fun recomputeOnly(): SyncOutcome {
        val startedAt = clock()
        val vin = state.get().vin
            ?: return failAt(startedAt, SyncError(SyncErrorKind.NO_VEHICLE, "No vehicle synchronized yet"))
        val tripsCosted = recomputeAndSave(vin)
        return SyncOutcome.Ok(0, 0, 0, tripsCosted, telemetryFailed = false, state.get())
    }

    private suspend fun recomputeAndSave(vin: String): Int {
        val tripList = trips.all(vin).map { it.toTrip() }
        val refuelList = refuels.all(vin)
        // The price is read per recompute, inside the mutex (docs/SPEC.md §12.1):
        // one read for the whole archive, never one per trip.
        val computed = recomputeCosts(tripList, refuelList, defaultPriceProvider(), config.tankCapacityL)
        writingTrips { costs.saveAll(computed) }
        return computed.size
    }

    // ---------------- helpers ----------------

    /**
     * Single flight plus the promise of docs/SPEC.md §8.5: outcomes, never exceptions.
     * Gateway errors are classified where they happen; whatever still escapes the
     * steps comes from the local archive (Room, a full disk) and becomes
     * [SyncErrorKind.STORAGE]. The message names only the exception class, never its
     * text (docs/SECURITY.md).
     */
    private suspend fun guarded(block: suspend () -> SyncOutcome): SyncOutcome {
        if (!mutex.tryLock()) return SyncOutcome.Skipped
        try {
            return try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failAt(clock(), SyncError(SyncErrorKind.STORAGE, "Local storage failed: ${e.javaClass.simpleName}"))
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Records [error] in [SyncState] and returns it. Tolerant of the archive it writes
     * to: when the state itself cannot be read or saved (the STORAGE case), the
     * outcome still reports the failure instead of throwing a second time.
     */
    private suspend fun failAt(startedAt: Instant, error: SyncError): SyncOutcome.Failed {
        val previous = try {
            state.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncState()
        }
        val updated = previous.copy(
            lastAttemptAt = startedAt,
            lastErrorAt = clock(),
            lastErrorKind = error.kind,
            lastErrorMessage = error.message,
            lastErrorStatus = error.status,
        )
        try {
            state.save(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing more to do: the returned outcome carries the failure.
        }
        return SyncOutcome.Failed(error, updated)
    }

    /**
     * Toyota exceptions -> stable kinds. Messages go through
     * [ToyotaSecrets.sanitize] so a stored message can never carry a VIN,
     * token or password (docs/SECURITY.md).
     */
    private fun classify(e: Exception): SyncError {
        if (e is CancellationException) throw e
        val kind = when (e) {
            is NoCredentialsException -> SyncErrorKind.NO_CREDENTIALS
            is ToyotaLoginException -> SyncErrorKind.LOGIN
            is ToyotaNetworkException -> SyncErrorKind.NETWORK
            is ToyotaApiException -> SyncErrorKind.API
            is ToyotaDataException -> SyncErrorKind.DATA
            else -> SyncErrorKind.DATA
        }
        val status = (e as? ToyotaApiException)?.statusCode?.takeIf { it in 100..599 }
        return SyncError(kind, ToyotaSecrets.sanitize(e.message ?: e.javaClass.simpleName), status)
    }
}
