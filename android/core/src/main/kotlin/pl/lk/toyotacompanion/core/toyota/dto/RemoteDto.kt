package pl.lk.toyotacompanion.core.toyota.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire models for the read-only Toyota data that is not part of the archive sync
 * (docs/SPEC.md §42-§44). Mirrors pytoyoda `models/endpoints/{status,location,vehicle_health}.py`
 * field aliases; every field is nullable and unknown fields are ignored, so a payload that
 * misses a part (or comes from a newer backend) never breaks the screen.
 */

/** pytoyoda status.py:59-95 — GET /v1/vehicle/status (cached in the Toyota cloud, not a live read). */
@Serializable
data class VehicleStatusResponse(val payload: RemoteStatusDto? = null)

@Serializable
data class RemoteStatusDto(
    val vin: String? = null,
    @SerialName("lastUpdateTimestamp") val lastUpdateTimestamp: String? = null,
    @SerialName("overallStatus") val overallStatus: String? = null,
    @SerialName("overallWarningCounts") val overallWarningCounts: Int? = null,
    @SerialName("leftHandDrive") val leftHandDrive: Boolean? = null,
    val doors: DoorsDto? = null,
    val windows: WindowsDto? = null,
)

/** pytoyoda status.py:18-31: the backend enum string plus when it was last observed. */
@Serializable
data class ComponentStateDto(
    val status: String? = null,
    @SerialName("lastUpdateTimestamp") val lastUpdateTimestamp: String? = null,
)

/** pytoyoda status.py:33-40: a door has a lock state and/or an open state (the hood reports open only). */
@Serializable
data class DoorDto(
    @SerialName("lockStatus") val lockStatus: ComponentStateDto? = null,
    @SerialName("openStatus") val openStatus: ComponentStateDto? = null,
)

@Serializable
data class DoorsDto(
    val driver: DoorDto? = null,
    val passenger: DoorDto? = null,
    @SerialName("rearLeft") val rearLeft: DoorDto? = null,
    @SerialName("rearRight") val rearRight: DoorDto? = null,
    @SerialName("rearBack") val rearBack: DoorDto? = null,
    val hood: DoorDto? = null,
)

@Serializable
data class WindowsDto(
    val driver: ComponentStateDto? = null,
    val passenger: ComponentStateDto? = null,
    @SerialName("rearLeft") val rearLeft: ComponentStateDto? = null,
    @SerialName("rearRight") val rearRight: ComponentStateDto? = null,
)

/** pytoyoda location.py:28-45 — GET /v1/location (last known position, updated when the car parks). */
@Serializable
data class LocationResponse(val payload: LocationDto? = null)

@Serializable
data class LocationDto(
    @SerialName("lastTimestamp") val lastTimestamp: String? = null,
    @SerialName("vehicleLocation") val vehicleLocation: VehicleLocationDto? = null,
    val vin: String? = null,
)

/** pytoyoda location.py:10-25 */
@Serializable
data class VehicleLocationDto(
    @SerialName("displayName") val displayName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("locationAcquisitionDatetime") val locationAcquisitionDatetime: String? = null,
)

/** pytoyoda vehicle_health.py:9-32 — GET /v1/vehiclehealth/status (warning lights, engine oil). */
@Serializable
data class VehicleHealthResponse(val payload: VehicleHealthDto? = null)

/**
 * pytoyoda vehicle_health.py:20-31. Both lists are untyped in the reference (`list[Any]`), so they are
 * kept as raw JSON: the screen counts the warnings and only says whether oil information came.
 */
@Serializable
data class VehicleHealthDto(
    @SerialName("quantityOfEngOilIcon") val quantityOfEngOilIcon: List<JsonElement>? = null,
    val vin: String? = null,
    val warning: List<JsonElement>? = null,
    @SerialName("wnglastUpdTime") val wnglastUpdTime: String? = null,
)
