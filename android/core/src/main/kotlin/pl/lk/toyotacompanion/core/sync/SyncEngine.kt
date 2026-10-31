package pl.lk.toyotacompanion.core.sync

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.TripCost
import pl.lk.toyotacompanion.core.domain.confirmRefuel as domainConfirmRefuel
import pl.lk.toyotacompanion.core.domain.detectRefuel
import pl.lk.toyotacompanion.core.domain.dismissRefuel as domainDismissRefuel
import pl.lk.toyotacompanion.core.domain.recomputeCosts
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
        state.save(state.get().copy(vin = vin))
    }

    /** What the UI shows: local data plus “current / not current”. */
    suspend fun status(): SyncStatus {
        val current = state.get()
        return SyncStatus(
            state = current,
            tripsStored = trips.count(),
            readingsStored = readings.count(),
            pendingRefuels = refuels.pending(current.vin).size,
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
        val costByTrip: Map<String, TripCost> = costs.all().associateBy { it.tripId }
        return trips.all(vin).asReversed().map { it.withCost(costByTrip[it.id]) }
    }

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
    ): RefuelChange = editRefuel(id) { stored ->
        domainConfirmRefuel(
            stored,
            pricePerLiter = pricePerLiter,
            liters = liters,
            totalCost = totalCost,
            filledToFull = filledToFull,
            litersSource = litersSource,
        )
    }

    /** Discards one pending refuel (docs/SPEC.md §3) and recounts the costs. */
    suspend fun dismissRefuel(id: String): RefuelChange = editRefuel(id) { domainDismissRefuel(it) }

    /**
     * The one write path of both edits. The write and the recount run under the
     * same [mutex] as the synchronization, so no other run can see a confirmed
     * refuel whose trip costs were not recomputed yet (docs/SPEC.md §11.1). A
     * rejection by the domain leaves the archive untouched, and
     * `CancellationException` is never caught here.
     */
    private suspend fun editRefuel(id: String, edit: (Refuel) -> Refuel): RefuelChange =
        mutex.withLock {
            val vin = state.get().vin ?: return@withLock RefuelChange.NoVehicle
            val stored = refuels.byId(id) ?: return@withLock RefuelChange.NotFound
            if (stored.status != RefuelStatus.PENDING) return@withLock RefuelChange.NotPending
            // An entry of another vehicle is invisible to this screen, so it counts as missing.
            if (refuels.pending(vin).none { it.id == id }) return@withLock RefuelChange.NotFound
            val edited = try {
                edit(stored)
            } catch (e: IllegalArgumentException) {
                return@withLock RefuelChange.Invalid
            }
            refuels.update(edited)
            RefuelChange.Done(edited, recomputeAndSave(vin))
        }

    // ---------------- steps ----------------

    private suspend fun syncNow(): SyncOutcome {
        val startedAt = clock()
        if (!hasCredentials()) {
            return failAt(startedAt, SyncError(SyncErrorKind.NO_CREDENTIALS, "No stored Toyota credentials"))
        }

        val stored = state.get()
        state.save(stored.copy(lastAttemptAt = startedAt))

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
        }

        // 3. Trips since the watermark, with an overlap for late-arriving trips.
        val today = LocalDate.ofInstant(clock(), ZoneOffset.UTC)
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
        val tripsAdded = trips.upsertAll(fetched)

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

        val done = state.get().copy(
            vin = vin,
            watermarkEpochDay = today.toEpochDay(),
            lastAttemptAt = startedAt,
            lastSuccessAt = clock(),
            lastErrorAt = null,
            lastErrorKind = null,
            lastErrorMessage = null,
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
        costs.saveAll(computed)
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
        return SyncError(kind, ToyotaSecrets.sanitize(e.message ?: e.javaClass.simpleName))
    }
}
