package pl.lk.toyotacompanion.core.toyota

import kotlinx.serialization.json.Json
import java.time.LocalDate
import pl.lk.toyotacompanion.core.toyota.dto.LocationDto
import pl.lk.toyotacompanion.core.toyota.dto.LocationResponse
import pl.lk.toyotacompanion.core.toyota.dto.RemoteStatusDto
import pl.lk.toyotacompanion.core.toyota.dto.TelemetryDto
import pl.lk.toyotacompanion.core.toyota.dto.TelemetryResponse
import pl.lk.toyotacompanion.core.toyota.dto.TripDetailResponse
import pl.lk.toyotacompanion.core.toyota.dto.TripDto
import pl.lk.toyotacompanion.core.toyota.dto.TripsPayloadDto
import pl.lk.toyotacompanion.core.toyota.dto.TripsResponse
import pl.lk.toyotacompanion.core.toyota.dto.VehicleDto
import pl.lk.toyotacompanion.core.toyota.dto.VehicleHealthDto
import pl.lk.toyotacompanion.core.toyota.dto.VehicleHealthResponse
import pl.lk.toyotacompanion.core.toyota.dto.VehicleStatusResponse
import pl.lk.toyotacompanion.core.toyota.dto.VehiclesResponse

/**
 * Toyota data endpoints (ported from pytoyoda `api.py`; only what ROADMAP NOW
 * requires). refresh_status / commands / Subaru intentionally absent
 * (AGENTS.md: never wake the car).
 */
class ToyotaApi(private val controller: ToyotaController) {
    private val json = Json { ignoreUnknownKeys = true }

    /** pytoyoda api.py:153-162 — GET /v2/vehicle/guid (no vin header). */
    suspend fun getVehicles(): List<VehicleDto> =
        decode<VehiclesResponse>(ToyotaConst.VEHICLE_GUID_ENDPOINT, vin = null).payload
            ?: emptyList()

    /** pytoyoda api.py:284-298 — GET /v3/telemetry with vin header. */
    suspend fun getTelemetry(vin: String): TelemetryDto =
        decode<TelemetryResponse>(ToyotaConst.TELEMETRY_ENDPOINT, vin = vin).payload
            ?: throw ToyotaDataException("Telemetry payload missing")

    /**
     * pytoyoda api.py:421-452 — GET /v1/trips (one page).
     * pytoyoda caps `limit` at 50 per the endpoint docs.
     */
    suspend fun getTrips(
        vin: String,
        from: LocalDate,
        to: LocalDate,
        summary: Boolean = true,
        limit: Int = 5,
        offset: Int = 0,
    ): TripsPayloadDto =
        decode<TripsResponse>(
            ToyotaConst.TRIPS_ENDPOINT
                .replace("{from_date}", from.toString())
                .replace("{to_date}", to.toString())
                .replace("{summary}", summary.toString())
                .replace("{limit}", limit.toString())
                .replace("{offset}", offset.toString()),
            vin = vin,
        ).payload ?: throw ToyotaDataException("Trips payload missing")

    /**
     * Follow `metadata.pagination.nextOffset` until the last page.
     * Decision outside the plan: convenience for the upcoming Room sync;
     * pytoyoda exposes only the single page.
     */
    suspend fun fetchAllTrips(
        vin: String,
        from: LocalDate,
        to: LocalDate,
        summary: Boolean = false,
        limitPerPage: Int = 50,
    ): List<TripDto> {
        val all = mutableListOf<TripDto>()
        val seenOffsets = mutableSetOf<Int>()
        var offset = 0
        while (true) {
            if (!seenOffsets.add(offset)) {
                throw ToyotaDataException("Pagination loop detected at offset $offset")
            }
            val page = getTrips(vin, from, to, summary, limitPerPage, offset)
            all += page.trips ?: emptyList()
            val next = page.metadata?.pagination?.nextOffset ?: break
            if (next < 0) {
                throw ToyotaDataException("Invalid pagination nextOffset: $next")
            }
            offset = next
        }
        return all
    }

    /**
     * pytoyoda api.py:455-477 — GET /v1/trips/{trip_id}; the only source of
     * per-trip routes since 2026-09-30 (const.py:63-64 comment).
     */
    suspend fun getTripDetails(vin: String, tripId: String): TripDto =
        decode<TripDetailResponse>(
            ToyotaConst.TRIP_DETAIL_ENDPOINT.replace("{trip_id}", tripId),
            vin = vin,
        ).payload ?: throw ToyotaDataException("Trip detail payload missing")

    /**
     * pytoyoda api.py:199-207 — GET /v1/vehicle/status. Read-only cloud cache (per-door lock/open,
     * per-window open, warning count); never wakes the car. A missing payload is null, not an error.
     */
    suspend fun getVehicleStatus(vin: String): RemoteStatusDto? =
        decode<VehicleStatusResponse>(ToyotaConst.VEHICLE_STATUS_ENDPOINT, vin = vin).payload

    /** pytoyoda api.py:166-180 — GET /v1/location: the last known position, updated when the car parks. */
    suspend fun getLocation(vin: String): LocationDto? =
        decode<LocationResponse>(ToyotaConst.VEHICLE_LOCATION_ENDPOINT, vin = vin).payload

    /** pytoyoda api.py:185-197 — GET /v1/vehiclehealth/status: warning entries and engine oil. */
    suspend fun getVehicleHealth(vin: String): VehicleHealthDto? =
        decode<VehicleHealthResponse>(ToyotaConst.VEHICLE_HEALTH_ENDPOINT, vin = vin).payload

    /**
     * SECURITY: the cause of a [ToyotaDataException] is a serialization error whose text can quote a piece of the answer, which for
     * `/v1/location` may be a coordinate or an address. It must never reach a log, a message on the screen or a file (docs/SECURITY.md).
     */
    private suspend inline fun <reified T> decode(endpoint: String, vin: String?): T {
        val body = controller.request("GET", endpoint, vin)
        return try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw ToyotaDataException("Cannot parse Toyota API response for $endpoint", e)
        }
    }
}
