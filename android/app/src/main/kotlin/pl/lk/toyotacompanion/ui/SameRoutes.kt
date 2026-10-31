package pl.lk.toyotacompanion.ui

import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord

/** Start and end of two trips this close make "the same route" (PRD F12, docs/SPEC.md §19). */
const val ROUTE_MATCH_M: Double = 300.0

/** A route needs this many trips to be shown and compared. */
const val MIN_ROUTE_TRIPS: Int = 3

/**
 * Trips driven between the same two places in the same direction, oldest first. The
 * group is matched against its first trip, the pattern; positions stay inside this
 * class and never reach the screen (docs/SECURITY.md, ADR-008).
 */
data class RouteGroup(val trips: List<StoredTrip>) {
    val pattern: TripRecord get() = trips.first().record
}

/**
 * Groups the trips of ONE vehicle into routes (PRD F12, docs/SPEC.md §19). Trips are taken
 * oldest first (start, id); each joins the first group whose pattern starts and ends within
 * [ROUTE_MATCH_M] of its own start and end, otherwise it opens a new group. Direction
 * matters: A→B and B→A are two routes. Trips without all four coordinates join nothing.
 * Only groups with at least [minTrips] trips are returned, the most driven first (ties:
 * the one driven most recently first).
 */
fun sameRoutes(trips: List<StoredTrip>, minTrips: Int = MIN_ROUTE_TRIPS): List<RouteGroup> {
    val ordered = trips.sortedWith(compareBy({ it.record.trip.start }, { it.record.trip.id }))
    // Each group keeps the ends of its pattern next to its trips: no `!!` anywhere.
    val groups = mutableListOf<Pair<Ends, MutableList<StoredTrip>>>()
    for (stored in ordered) {
        val ends = stored.record.ends() ?: continue
        val home = groups.firstOrNull { (pattern, _) -> pattern.matches(ends) }
        if (home != null) home.second += stored else groups += ends to mutableListOf(stored)
    }
    return groups
        .map { it.second }
        .filter { it.size >= minTrips }
        .sortedWith(
            compareByDescending<List<StoredTrip>> { it.size }
                .thenByDescending { group -> group.last().record.trip.start },
        )
        .map { RouteGroup(it) }
}

private data class Ends(val startLat: Double, val startLon: Double, val endLat: Double, val endLon: Double) {
    fun matches(other: Ends): Boolean =
        distanceMeters(startLat, startLon, other.startLat, other.startLon) <= ROUTE_MATCH_M &&
            distanceMeters(endLat, endLon, other.endLat, other.endLon) <= ROUTE_MATCH_M
}

private fun TripRecord.ends(): Ends? {
    val values = listOf(startLat, startLon, endLat, endLon)
    if (values.any { it == null || !it.isFinite() }) return null
    return Ends(startLat!!, startLon!!, endLat!!, endLon!!)
}
