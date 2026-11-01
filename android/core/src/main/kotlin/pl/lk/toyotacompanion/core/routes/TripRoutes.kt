package pl.lk.toyotacompanion.core.routes

import pl.lk.toyotacompanion.core.toyota.ToyotaApi
import pl.lk.toyotacompanion.core.toyota.dto.RoutePointDto

/** One GPS point of a drive; [isEv] says whether the car ran on the battery there (`null` when Toyota did not say). */
data class TripRoutePoint(val lat: Double, val lon: Double, val isEv: Boolean?)

/** Source of the GPS route of one trip (docs/SPEC.md §27). Read-only: a GET of the trip detail, never `refresh_status`. */
fun interface TripRouteGateway {
    /** The route of [tripId] of [vin], possibly empty when Toyota keeps no route for it. */
    suspend fun route(vin: String, tripId: String): List<TripRoutePoint>
}

/** Route points the map can use: finite and inside the valid range; anything else is dropped, never clamped. */
fun routePointsOf(dtos: List<RoutePointDto>?): List<TripRoutePoint> = dtos.orEmpty().mapNotNull { dto ->
    val lat = dto.lat ?: return@mapNotNull null
    val lon = dto.lon ?: return@mapNotNull null
    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
    TripRoutePoint(lat, lon, dto.isEv)
}

/** [TripRouteGateway] on the real client; the DTOs never leave `core`. */
class ToyotaTripRouteGateway(private val api: ToyotaApi) : TripRouteGateway {
    override suspend fun route(vin: String, tripId: String): List<TripRoutePoint> =
        routePointsOf(api.getTripDetails(vin, tripId).route)
}

/**
 * Stored routes by trip id, so a trip is asked for from Toyota once. Toyota keeps trips for about 12 months, so what is
 * stored here is the only copy after that. An empty list is a stored "Toyota has no route for it".
 */
interface TripRouteRepository {
    suspend fun get(tripId: String): List<TripRoutePoint>?

    suspend fun save(tripId: String, points: List<TripRoutePoint>)

    suspend fun count(): Int

    suspend fun deleteAll()
}

/**
 * Compact text of a route for the database: `lat,lon,e` per point (five decimals, about 1 m; `e` is 1, 0 or `-`),
 * points separated by `;`. Round-trips through [decodeRoute]; a damaged piece is skipped.
 */
fun encodeRoute(points: List<TripRoutePoint>): String = points.joinToString(";") {
    "%.5f,%.5f,%s".format(java.util.Locale.ROOT, it.lat, it.lon, when (it.isEv) { true -> "1"; false -> "0"; null -> "-" })
}

fun decodeRoute(text: String): List<TripRoutePoint> {
    if (text.isEmpty()) return emptyList()
    return text.split(';').mapNotNull { piece ->
        val a = piece.split(',')
        if (a.size != 3) return@mapNotNull null
        val lat = a[0].toDoubleOrNull() ?: return@mapNotNull null
        val lon = a[1].toDoubleOrNull() ?: return@mapNotNull null
        if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
        TripRoutePoint(lat, lon, when (a[2]) { "1" -> true; "0" -> false; else -> null })
    }
}
