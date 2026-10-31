package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Below this distance a share is noise (plan C11 D3). Mirrors Python `MIN_RANKED_DISTANCE_KM`. */
val MIN_RANKED_DISTANCE_KM: BigDecimal = BigDecimal("1")

/** How many trips the electric ranking shows. */
const val RANKING_LIMIT: Int = 10

private val HUNDRED = BigDecimal(100)
private val ZERO = BigDecimal(0)

/**
 * `ev / distance`, rounded to 0.1 % (PRD F11, docs/SPEC.md §16). Toyota rounds meters, so
 * `ev > distance` is clipped to 100 % and marked [TripShare.inconsistent]; no `hdc` or no
 * distance gives `null`. Mirrors Python `electric_share`.
 */
fun electricShare(trip: Trip): TripShare {
    val distance = toBigDecimal(trip.distanceKm)
    val evKm = trip.evDistanceKm
    if (evKm == null || distance <= ZERO) {
        return TripShare(trip.id, roundDistance(distance), evKm?.let { roundDistance(toBigDecimal(it)) }, null, false)
    }
    val ev = toBigDecimal(evKm)
    val inconsistent = ev > distance
    val share = if (inconsistent) HUNDRED else roundPercent(ev.multiply(HUNDRED).divideLikePythonDecimal(distance))
    return TripShare(trip.id, roundDistance(distance), roundDistance(ev), share, inconsistent)
}

/** `sum(ev) / sum(distance)` over the trips with `hdc` data; `null` when none has it. Mirrors Python. */
fun aggregateElectricShare(trips: List<Trip>): BigDecimal? {
    val withData = trips.filter { it.evDistanceKm != null }
    val distance = withData.fold(ZERO) { acc, trip -> acc.add(toBigDecimal(trip.distanceKm)) }
    if (distance <= ZERO) return null
    val ev = withData.fold(ZERO) { acc, trip -> acc.add(toBigDecimal(requireNotNull(trip.evDistanceKm))) }
    return roundPercent(ev.min(distance).multiply(HUNDRED).divideLikePythonDecimal(distance))
}

/**
 * Trips with the highest electric share first; ties by distance (longer first), then id.
 * Trips without `hdc` data and trips shorter than [minDistanceKm] are only counted.
 * Mirrors Python `electric_trip_ranking`.
 */
fun electricTripRanking(
    trips: List<Trip>,
    limit: Int = RANKING_LIMIT,
    minDistanceKm: BigDecimal = MIN_RANKED_DISTANCE_KM,
): ElectricRanking {
    var withoutData = 0
    var tooShort = 0
    val ranked = mutableListOf<TripShare>()
    for (trip in trips) {
        val share = electricShare(trip)
        when {
            share.sharePct == null -> withoutData++
            toBigDecimal(trip.distanceKm) < minDistanceKm -> tooShort++
            else -> ranked += share
        }
    }
    val ordered = ranked.sortedWith(
        compareByDescending<TripShare> { it.sharePct ?: ZERO }
            .thenByDescending { it.distanceKm }
            .thenBy { it.tripId },
    )
    return ElectricRanking(ordered.take(limit), withoutData, tooShort)
}

/** Meteorological season of a calendar month (1..12). Mirrors Python `season_of`. */
fun seasonOf(month: Int): Season = when (month) {
    12, 1, 2 -> Season.WINTER
    3, 4, 5 -> Season.SPRING
    6, 7, 8 -> Season.SUMMER
    else -> Season.AUTUMN
}

private fun periodKey(day: LocalDate, kind: PeriodKind): Pair<Int, Season?> = when (kind) {
    PeriodKind.YEAR -> day.year to null
    // December opens the winter that continues in January: it is named by January's year.
    PeriodKind.SEASON -> (if (day.monthValue == 12) day.year + 1 else day.year) to seasonOf(day.monthValue)
}

/**
 * Statistics per year or per season, oldest first (PRD F10, docs/SPEC.md §17). A trip belongs
 * to the period of its start in [zone], the zone of the trips list. Costs only from trips with
 * a price (`source != NONE` and an amount); with none the cost is `null`, never 0 zł.
 * Mirrors Python `period_stats`.
 */
fun periodStats(trips: List<Trip>, costs: List<TripCost>, zone: ZoneId, kind: PeriodKind): List<PeriodStats> {
    val byTrip = costs.associateBy { it.tripId }
    val groups = LinkedHashMap<Pair<Int, Season?>, MutableList<Trip>>()
    for (trip in trips) {
        groups.getOrPut(periodKey(trip.start.atZone(zone).toLocalDate(), kind)) { mutableListOf() } += trip
    }
    return groups.entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second?.ordinal ?: 0 }))
        .map { (key, members) -> stats(key.first, key.second, members, byTrip) }
}

private fun stats(year: Int, season: Season?, members: List<Trip>, byTrip: Map<String, TripCost>): PeriodStats {
    val distance = members.fold(ZERO) { acc, trip -> acc.add(toBigDecimal(trip.distanceKm)) }
    val fuel = members.fold(ZERO) { acc, trip -> acc.add(trip.fuelL) }
    val priced = members.mapNotNull { trip ->
        byTrip[trip.id]?.takeIf { it.costPln != null && it.source != CostSource.NONE }?.let { trip to it }
    }
    val costSum = if (priced.isEmpty()) null else priced.fold(ZERO) { acc, (_, cost) -> acc.add(requireNotNull(cost.costPln)) }
    val pricedDistance = priced.fold(ZERO) { acc, (trip, _) -> acc.add(toBigDecimal(trip.distanceKm)) }
    return PeriodStats(
        year = year,
        season = season,
        trips = members.size,
        distanceKm = roundDistance(distance),
        fuelL = fuel,
        lPer100Km = if (distance > ZERO) roundConsumption(fuel.multiply(HUNDRED).divideLikePythonDecimal(distance)) else null,
        costPln = costSum?.let { roundMoney(it) },
        tripsWithoutCost = members.size - priced.size,
        costPer100Km = if (costSum != null && pricedDistance > ZERO) {
            roundMoney(costSum.multiply(HUNDRED).divideLikePythonDecimal(pricedDistance))
        } else {
            null
        },
        evSharePct = aggregateElectricShare(members),
        averageTripKm = if (members.isEmpty()) null else roundDistance(distance.divideLikePythonDecimal(BigDecimal(members.size))),
    )
}

/**
 * The latest year so far against the same days of the year before; `null` without trips in
 * that earlier window. The window runs from 1 January to the day of the latest trip in both
 * years — otherwise January of a new year would look like a 92 % drop. Mirrors Python
 * `year_over_year`.
 */
fun yearOverYear(trips: List<Trip>, costs: List<TripCost>, zone: ZoneId): Pair<PeriodStats, PeriodStats>? {
    if (trips.isEmpty()) return null
    val latest = trips.maxOf { it.start.atZone(zone).toLocalDate() }
    fun window(year: Int): List<Trip> = trips.filter { trip ->
        val local = trip.start.atZone(zone).toLocalDate()
        local.year == year &&
            (local.monthValue < latest.monthValue ||
                (local.monthValue == latest.monthValue && local.dayOfMonth <= latest.dayOfMonth))
    }
    val previous = window(latest.year - 1)
    if (previous.isEmpty()) return null
    val byTrip = costs.associateBy { it.tripId }
    return stats(latest.year, null, window(latest.year), byTrip) to stats(latest.year - 1, null, previous, byTrip)
}
