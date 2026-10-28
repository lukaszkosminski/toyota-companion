package pl.lk.toyotacompanion.core.toyota

import kotlinx.serialization.json.Json
import java.time.LocalDate
import pl.lk.toyotacompanion.core.toyota.dto.TelemetryDto
import pl.lk.toyotacompanion.core.toyota.dto.TelemetryResponse
import pl.lk.toyotacompanion.core.toyota.dto.TripDetailResponse
import pl.lk.toyotacompanion.core.toyota.dto.TripDto
import pl.lk.toyotacompanion.core.toyota.dto.TripsPayloadDto
import pl.lk.toyotacompanion.core.toyota.dto.TripsResponse
import pl.lk.toyotacompanion.core.toyota.dto.VehicleDto
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
        var offset = 0
        while (true) {
            val page = getTrips(vin, from, to, summary, limitPerPage, offset)
            all += page.trips ?: emptyList()
            offset = page.metadata?.pagination?.nextOffset ?: break
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

    private suspend inline fun <reified T> decode(endpoint: String, vin: String?): T {
        val body = controller.request("GET", endpoint, vin)
        return try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw ToyotaDataException("Cannot parse Toyota API response for $endpoint", e)
        }
    }
}
