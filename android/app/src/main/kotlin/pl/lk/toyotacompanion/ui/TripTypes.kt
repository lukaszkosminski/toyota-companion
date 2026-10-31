package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/** How the car used a trip (PRD F16, docs/SPEC.md §19, §20). */
enum class TripType { COMMUTE, ERRAND, WEEKEND, OTHER }

/** The place the car sleeps in becomes "home" only after this many stops there. */
const val MIN_HOME_STOPS: Int = 5

/** A second place becomes "work" only after this many working-day mornings there. */
const val MIN_WORK_STOPS: Int = 5

/** A morning only counts as work when the car stayed at least this long. */
val WORK_MIN_STAY: Duration = Duration.ofHours(4)

/** A stop shorter than this after a trip is an errand, not a night away from home. */
val ERRAND_MAX_STAY: Duration = Duration.ofHours(2)

/** The first hour and the last hour of a working-day morning. */
private const val WORK_MORNING_FIRST_HOUR = 6
private const val WORK_MORNING_LAST_HOUR = 10

/**
 * Trip types (docs/PRD.md F16): what the car was used for, recognised on its own.
 *
 * Like the parking diary of F17 this is a pure function over what is already in the archive -
 * no position is ever requested from Toyota, which would need the forbidden `refresh_status()`
 * (AGENTS.md). The places are never named by hand and never shown as a coordinate here: the
 * labels "Dom" and "Praca" are attached to a place of the diary, and this file hands out only
 * a type per trip (docs/SECURITY.md, ADR-008).
 */

/** The two places the app recognised, either of them absent when the archive is too thin. */
data class KnownPlaces(
    val home: ParkingPlace?,
    val work: ParkingPlace?,
)

/** One row of the "Typy tras" summary: how many trips of that type, how far, what for. */
data class TripTypeSummary(
    val type: TripType,
    val trips: Int,
    val distanceKm: BigDecimal,
    val costPln: BigDecimal?,
)

/**
 * The home and the work place of a diary (PRD F16, docs/SPEC.md §20).
 *
 * Home is the place the car returned to most (the diary lists places by total time already),
 * once it has stood there [MIN_HOME_STOPS] times. Work is the other place with the most
 * working-day mornings: a stop that started on Monday-Friday between [WORK_MORNING_FIRST_HOUR]
 * and [WORK_MORNING_LAST_HOUR] in [zone] and lasted at least [WORK_MIN_STAY] - the shape of a
 * job, not a name anybody has to type. A tie goes to the place with the longer total stay, and
 * fewer than [MIN_WORK_STOPS] such mornings leaves the second place unknown.
 */
fun knownPlaces(log: ParkingLog, zone: ZoneId): KnownPlaces {
    val home = log.places.firstOrNull()?.takeIf { it.stops >= MIN_HOME_STOPS }
    val work = log.places
        .filter { it != home }
        .map { place -> place to workingDayStops(log.stops, place, zone) }
        .sortedWith(
            compareByDescending<Pair<ParkingPlace, Int>> { row -> row.second }
                .thenByDescending { row -> row.first.total },
        )
        .firstOrNull()
        ?.takeIf { (_, mornings) -> mornings >= MIN_WORK_STOPS }
        ?.first
    return KnownPlaces(home, work)
}

/**
 * Type of every trip, keyed by the trip id. Trips are read oldest first, because an errand is
 * recognised from the stop that follows it (docs/SPEC.md §20):
 *
 *  - [TripType.COMMUTE]: between home and work, in either direction. Without a recognised
 *    second place nothing is a commute - a trip to work would otherwise be an errand.
 *  - [TripType.WEEKEND]: the trip starts on Saturday or Sunday in [zone].
 *  - [TripType.ERRAND]: the trip does not end at home or at work and the next trip starts
 *    less than [ERRAND_MAX_STAY] later - the car stood somewhere for a while and came back.
 *  - [TripType.OTHER]: everything else, including the last trip of the archive, whose stop
 *    we cannot see because there is no trip after it.
 *
 * A trip whose coordinates are missing, or not a number, is near nothing rather than near
 * anything, so it can still be a weekend trip or an errand, just never a commute.
 */
fun classifyTrips(trips: List<StoredTrip>, places: KnownPlaces, zone: ZoneId): Map<String, TripType> {
    val ordered = trips.sortedWith(compareBy({ it.record.trip.start }, { it.record.trip.id }))
    val types = LinkedHashMap<String, TripType>(ordered.size)
    for (index in ordered.indices) {
        val stored = ordered[index]
        val startsAtHome = startsNear(stored, places.home)
        val endsAtHome = endsNear(stored, places.home)
        val startsAtWork = startsNear(stored, places.work)
        val endsAtWork = endsNear(stored, places.work)
        val next = ordered.getOrNull(index + 1)
        val stay = next?.let { Duration.between(stored.record.trip.end, it.record.trip.start) }
        val type = when {
            (startsAtHome && endsAtWork) || (startsAtWork && endsAtHome) -> TripType.COMMUTE
            isWeekend(stored, zone) -> TripType.WEEKEND
            !endsAtHome && !endsAtWork && stay != null && stay < ERRAND_MAX_STAY -> TripType.ERRAND
            else -> TripType.OTHER
        }
        types[stored.record.trip.id] = type
    }
    return types
}

/**
 * The summary rows in the order the screen wants them: [TripType.COMMUTE], [TripType.ERRAND],
 * [TripType.WEEKEND], [TripType.OTHER]; a type without trips is left out rather than shown as
 * zero. Distances are 0.1 km, [hasNoCost] trips add no money and a type without any priced
 * trip gets `null`, the same "brak ceny" the trips list uses (docs/SPEC.md §10.2).
 */
fun tripTypeSummaries(trips: List<StoredTrip>, types: Map<String, TripType>): List<TripTypeSummary> {
    val rows = ArrayList<TripTypeSummary>(TripType.entries.size)
    for (type in TripType.entries) {
        val ofType = trips.filter { types[it.record.trip.id] == type }
        if (ofType.isEmpty()) continue
        var km = BigDecimal.ZERO
        for (stored in ofType) {
            val kilometres = stored.record.trip.distanceKm
            if (kilometres.isFinite() && kilometres > 0.0) km = km.add(toBigDecimal(kilometres))
        }
        var cost: BigDecimal? = null
        for (stored in ofType) {
            val value = stored.cost?.costPln ?: continue
            if (hasNoCost(stored.cost)) continue
            cost = cost?.add(value) ?: value
        }
        rows += TripTypeSummary(
            type = type,
            trips = ofType.size,
            distanceKm = km.setScale(1, RoundingMode.HALF_UP),
            costPln = cost?.setScale(2, RoundingMode.HALF_UP),
        )
    }
    return rows
}

/** Stops of the diary that stand at [place] and have the shape of a working day. */
private fun workingDayStops(stops: List<ParkingStop>, place: ParkingPlace, zone: ZoneId): Int {
    var mornings = 0
    for (stop in stops) {
        if (!near(stop.lat, stop.lon, place)) continue
        val local = stop.from.atZone(zone)
        if (local.dayOfWeek !in DayOfWeek.MONDAY..DayOfWeek.FRIDAY) continue
        if (local.hour !in WORK_MORNING_FIRST_HOUR..WORK_MORNING_LAST_HOUR) continue
        if (stop.duration < WORK_MIN_STAY) continue
        mornings++
    }
    return mornings
}

private fun isWeekend(stored: StoredTrip, zone: ZoneId): Boolean {
    val day = stored.record.trip.start.atZone(zone).dayOfWeek
    return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
}

private fun startsNear(stored: StoredTrip, place: ParkingPlace?): Boolean =
    near(stored.record.startLat, stored.record.startLon, place)

private fun endsNear(stored: StoredTrip, place: ParkingPlace?): Boolean =
    near(stored.record.endLat, stored.record.endLon, place)

/** The same 150 m the diary uses for its places; an unknown position is near nothing. */
private fun near(lat: Double?, lon: Double?, place: ParkingPlace?): Boolean {
    if (place == null || lat == null || lon == null) return false
    if (!lat.isFinite() || !lon.isFinite()) return false
    if (!place.lat.isFinite() || !place.lon.isFinite()) return false
    return distanceMeters(place.lat, place.lon, lat, lon) <= PARKING_MATCH_M
}
