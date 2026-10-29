package pl.lk.toyotacompanion.core.data

/**
 * Vehicles known to the account (`GET /v2/vehicle/guid`).
 *
 * On update the stored `firstSeen` is kept and only the description plus
 * `lastSeen` change — the archive must not forget when a car first appeared.
 */
interface VehicleRepository {
    suspend fun upsert(vehicle: VehicleRecord)

    suspend fun byVin(vin: String): VehicleRecord?

    suspend fun all(): List<VehicleRecord>

    suspend fun count(): Int

    suspend fun deleteAll()
}
