package pl.lk.toyotacompanion.core.toyota.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models (JSON) for the ported endpoints. Mirrors pytoyoda
 * `models/endpoints/{vehicle_guid,telemetry,trips,common}.py` field aliases.
 * Unknown fields are ignored at parse time.
 */

@Serializable
data class VehiclesResponse(val payload: List<VehicleDto>? = null)

/**
 * pytoyoda vehicle_guid.py:378-569 (VehicleGuidModel, trimmed).
 * NOTE: the payload has no literal "guid" field — the stable identifiers are
 * subscriberGuid/remoteUserGuid plus vin (verified in fixture + model).
 */
@Serializable
data class VehicleDto(
    val vin: String? = null,
    val brand: String? = null,
    @SerialName("modelName") val modelName: String? = null,
    @SerialName("subscriberGuid") val subscriberGuid: String? = null,
    @SerialName("remoteUserGuid") val remoteUserGuid: String? = null,
    @SerialName("fuelType") val fuelType: String? = null,
    @SerialName("evVehicle") val evVehicle: Boolean? = null,
)

val VehicleDto.guid: String? get() = subscriberGuid ?: remoteUserGuid

@Serializable
data class TelemetryResponse(val payload: TelemetryDto? = null)

/** pytoyoda telemetry.py:24-32 */
@Serializable
data class TelemetryDto(
    @SerialName("fuelType") val fuelType: String? = null,
    val odometer: UnitValueDto? = null,
    @SerialName("fuelLevel") val fuelLevel: Int? = null,
    @SerialName("batteryLevel") val batteryLevel: Int? = null,
    @SerialName("distanceToEmpty") val distanceToEmpty: UnitValueDto? = null,
    val timestamp: String? = null,
)

/** pytoyoda common.py:19-21 (UnitValueModel) */
@Serializable
data class UnitValueDto(val unit: String? = null, val value: Double? = null)

@Serializable
data class TripsResponse(val payload: TripsPayloadDto? = null)

/** pytoyoda trips.py:197-206 (TripsModel) */
@Serializable
data class TripsPayloadDto(
    @SerialName("_metadata") val metadata: MetadataDto? = null,
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
    val trips: List<TripDto>? = null,
)

/** pytoyoda trips.py:184-190 (_MetadataModel) */
@Serializable
data class MetadataDto(val pagination: PaginationDto? = null)

/** pytoyoda trips.py:172-181 (_PaginationModel, trimmed) */
@Serializable
data class PaginationDto(
    val limit: Int? = null,
    val offset: Int? = null,
    @SerialName("nextOffset") val nextOffset: Int? = null,
    @SerialName("currentPage") val currentPage: Int? = null,
    @SerialName("totalCount") val totalCount: Int? = null,
    @SerialName("pageCount") val pageCount: Int? = null,
)

@Serializable
data class TripDetailResponse(val payload: TripDto? = null)

/** pytoyoda trips.py:143-150 (_TripModel, trimmed to ported fields) */
@Serializable
data class TripDto(
    val id: String? = null,
    val category: Int? = null,
    val summary: TripSummaryDto? = null,
    val hdc: HdcDto? = null,
)

/** pytoyoda trips.py:21-37 (_SummaryBaseModel) + 88-99 (_SummaryModel ts fields) */
@Serializable
data class TripSummaryDto(
    val length: Long? = null,
    val duration: Long? = null,
    @SerialName("averageSpeed") val averageSpeed: Double? = null,
    @SerialName("fuelConsumption") val fuelConsumption: Double? = null,
    @SerialName("startTs") val startTs: String? = null,
    @SerialName("endTs") val endTs: String? = null,
)

/** pytoyoda trips.py:115-117 (_HDCModel, trimmed) */
@Serializable
data class HdcDto(
    @SerialName("evTime") val evTime: Long? = null,
    @SerialName("evDistance") val evDistance: Long? = null,
)
