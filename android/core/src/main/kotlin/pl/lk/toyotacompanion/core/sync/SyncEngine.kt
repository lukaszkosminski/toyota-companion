package pl.lk.toyotacompanion.core.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import pl.lk.toyotacompanion.core.data.CostRepository
import pl.lk.toyotacompanion.core.data.FuelReadingRepository
import pl.lk.toyotacompanion.core.data.RefuelRepository
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.SyncConfig
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncStateRepository
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.TripRepository
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.data.VehicleRepository
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.TripCost
import pl.lk.toyotacompanion.core.domain.detectRefuel
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
    private val clock: () -> Instant = { Instant.now() },
    private val hasCredentials: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()

    /** Full pipeline. [SyncOutcome.Skipped] when another run holds the single flight. */
    suspend fun fullSync(): SyncOutcome = guarded { syncNow() }

    /** Cost recount only, no network — call after the user confirms a price (PRD F4). */
    suspend fun recomputeCosts(): SyncOutcome = guarded { recomputeOnly() }

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
        try {
            val reading = gateway.telemetry(vin)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            telemetryFailed = true
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
        } catch (e: Exception) {
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
        val computed = recomputeCosts(tripList, refuelList, config.defaultPrice, config.tankCapacityL)
        costs.saveAll(computed)
        return computed.size
    }

    // ---------------- helpers ----------------

    private suspend fun guarded(block: suspend () -> SyncOutcome): SyncOutcome {
        if (!mutex.tryLock()) return SyncOutcome.Skipped
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun failAt(startedAt: Instant, error: SyncError): SyncOutcome.Failed {
        val updated = state.get().copy(
            lastAttemptAt = startedAt,
            lastErrorAt = clock(),
            lastErrorKind = error.kind,
            lastErrorMessage = error.message,
        )
        state.save(updated)
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
