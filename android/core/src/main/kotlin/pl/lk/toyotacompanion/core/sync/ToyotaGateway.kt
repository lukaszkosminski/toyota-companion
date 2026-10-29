package pl.lk.toyotacompanion.core.sync

import java.time.Instant
import java.time.LocalDate
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.toyota.ToyotaApi
import pl.lk.toyotacompanion.core.toyota.ToyotaMapper

/**
 * Everything the synchronizer may ask Toyota for (docs/SPEC.md §8.1).
 *
 * Deliberately narrow: no `refresh_status` and no commands — they drain the
 * 12 V battery (AGENTS.md, docs/PRD.md §6). Network/auth failures propagate as
 * the typed exceptions of `core.toyota`; [SyncEngine] turns them into
 * [SyncOutcome.Failed].
 */
interface ToyotaGateway {
    /** Vehicles of the account, mapped and free of null VINs. */
    suspend fun vehicles(): List<VehicleRecord>

    /**
     * Current telemetry as one reading, or null when the payload carries no
     * usable fuel level / timestamp / odometer (docs/ROADMAP.md).
     */
    suspend fun telemetry(vin: String): FuelReading?

    /** Trips of [vin] whose start falls into `[from, to]` (inclusive), all pages. */
    suspend fun trips(vin: String, from: LocalDate, to: LocalDate): List<TripRecord>
}

/**
 * [ToyotaGateway] on the real client. The only place where the synchronizer
 * touches `core.toyota`; DTOs never leave it.
 *
 * HTTP client and thread lifetimes belong to the owner of [ToyotaApi] (the app
 * module creates it once and reuses it) — a synchronization must not shut down
 * a client it did not create.
 */
class ToyotaApiGateway(
    private val api: ToyotaApi,
    private val now: () -> Instant = { Instant.now() },
) : ToyotaGateway {

    override suspend fun vehicles(): List<VehicleRecord> {
        val seen = now()
        return api.getVehicles().mapNotNull { dto -> ToyotaMapper.toVehicleRecord(dto, seen) }
    }

    override suspend fun telemetry(vin: String): FuelReading? =
        ToyotaMapper.toFuelReading(api.getTelemetry(vin))

    override suspend fun trips(vin: String, from: LocalDate, to: LocalDate): List<TripRecord> {
        val synced = now()
        return api.fetchAllTrips(vin, from, to).mapNotNull { dto ->
            ToyotaMapper.toTripRecord(dto, vin, synced)
        }
    }
}
