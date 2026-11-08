package pl.lk.toyotacompanion.core.vehicle

import pl.lk.toyotacompanion.core.domain.VehicleHealth
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus
import pl.lk.toyotacompanion.core.toyota.ToyotaApi
import pl.lk.toyotacompanion.core.toyota.VehicleDataMapper

/**
 * The read-only Toyota data outside the archive (docs/SPEC.md §42-§44): vehicle status, last known
 * position and vehicle health. These endpoints read the Toyota cloud cache — no `refresh_status()`,
 * no command, so the car is never woken (AGENTS.md, docs/PRD.md §6). Failures propagate as the typed
 * exceptions of `core.toyota`; the screens turn them into "Toyota did not share this data".
 */
interface VehicleDataGateway {
    suspend fun status(vin: String): VehicleStatus?
    suspend fun location(vin: String): VehicleLocation?
    suspend fun health(vin: String): VehicleHealth?
}

/** [VehicleDataGateway] on the real client; the [ToyotaApi] (and its HTTP client) is owned by the caller. */
class ToyotaVehicleDataGateway(private val api: ToyotaApi) : VehicleDataGateway {
    override suspend fun status(vin: String): VehicleStatus? =
        VehicleDataMapper.toVehicleStatus(api.getVehicleStatus(vin))

    override suspend fun location(vin: String): VehicleLocation? =
        VehicleDataMapper.toVehicleLocation(api.getLocation(vin))

    override suspend fun health(vin: String): VehicleHealth? =
        VehicleDataMapper.toVehicleHealth(api.getVehicleHealth(vin))
}
