package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/** A route needs this many usable drives before its usual consumption means anything. */
const val MIN_ANOMALY_GROUP: Int = 5

/** Below this distance one litre of fuel is not a consumption figure, it is a rounding error. */
val MIN_ANOMALY_DISTANCE_KM: BigDecimal = BigDecimal("2")

/** A drive this percent above the usual value of its route is worth showing (PRD F13). */
val ANOMALY_THRESHOLD_PCT: BigDecimal = BigDecimal("25")

/** How many anomalies the screen lists. */
const val ANOMALY_LIMIT: Int = 20

private val HUNDRED = BigDecimal(100)
private val TWO = BigDecimal(2)
private val ZERO = BigDecimal.ZERO

/**
 * Driving style over time (PRD F12) and fuel anomalies (PRD F13), as pure functions of the
 * archived trips. Like the parking diary (ADR-008) these stay in `app` on [StoredTrip]:
 * `scoreGlobal` and GPS live in the data layer and the domain model is deliberately free of
 * them. Nothing here asks Toyota for anything (`refresh_status()` is forbidden, AGENTS.md),
 * and no position is ever read out of a route group, so no coordinate can reach a screen.
 */

/** One month of the eco score: the distance-weighted average and how many trips scored. */
data class MonthScore(
    val month: MonthKey,
    val score: BigDecimal,
    val trips: Int,
)

/** One drive that used noticeably more fuel than its route usually does (PRD F13, docs/SPEC.md §19). */
data class FuelAnomaly(
    val trip: StoredTrip,
    val routeIndex: Int,
    val lPer100Km: BigDecimal,
    val usualLPer100Km: BigDecimal,
    val excessPct: BigDecimal,
)

/**
 * Eco score per month (PRD F12, docs/SPEC.md §19): the average of `scoreGlobal` weighted by
 * the distance of the trips that have a score, to 0.1. A trip without a score is left out of
 * both the average and the count, a month without any score does not appear at all - an
 * absent month is honest, a month of "0.0 pkt" would look like a catastrophic month.
 *
 * The month is `trip.start` in [zone], the zone of the trips list, exactly like
 * [groupTripsByMonth], so a trip that starts after midnight in Warsaw counts to that day.
 * Newest month first.
 */
fun ecoScoreByMonth(trips: List<StoredTrip>, zone: ZoneId): List<MonthScore> {
    val scored = LinkedHashMap<MonthKey, MutableList<Pair<Int, BigDecimal>>>()
    for (stored in trips) {
        val score = stored.record.scoreGlobal ?: continue
        val distance = finiteDistance(stored) ?: continue
        val local = stored.record.trip.start.atZone(zone)
        scored.getOrPut(MonthKey(local.year, local.monthValue)) { mutableListOf() } += score to distance
    }
    return scored.entries
        .sortedByDescending { it.key }
        .map { (month, rows) ->
            val weight = rows.fold(ZERO) { total, (_, distance) -> total.add(distance) }
            // Half up at ten places, then at the shown one: the contract of the app layer,
            // which has no access to the division of the domain (docs/ARCHITECTURE.md).
            val points = rows.fold(ZERO) { total, (score, distance) ->
                total.add(BigDecimal(score).multiply(distance))
            }.divide(weight, 10, RoundingMode.HALF_UP)
            MonthScore(month, points.setScale(1, RoundingMode.HALF_UP), rows.size)
        }
}

/**
 * Consumption of one drive in l/100 km to 0.01, or `null` when the drive is too short to
 * carry a figure (under [MIN_ANOMALY_DISTANCE_KM]), burned nothing, or the archive holds a
 * distance that is not a number. A `null` is never shown as "0,00": a short city hop is not
 * an economical one.
 */
fun routeConsumption(trip: StoredTrip): BigDecimal? {
    val distance = finiteDistance(trip) ?: return null
    if (distance < MIN_ANOMALY_DISTANCE_KM) return null
    val fuel = trip.record.trip.fuelL
    if (fuel.signum() <= 0) return null
    val perHundred = fuel.multiply(HUNDRED).divide(distance, 10, RoundingMode.HALF_UP)
    return perHundred.setScale(2, RoundingMode.HALF_UP)
}

/**
 * The usual consumption of a route: the median of the drives that have one, to 0.01, the
 * mean of the two middle values for an even number of drives. The median, not the mean, so
 * that one already anomalous drive does not move the bar it is measured against. `null`
 * when no drive of the route has a consumption; `0.01` steps keep the percent of the
 * anomaly stable.
 */
fun medianConsumption(group: RouteGroup): BigDecimal? {
    val values = group.trips.mapNotNull { routeConsumption(it) }.sorted()
    if (values.isEmpty()) return null
    val middle = values.size / 2
    if (values.size % 2 == 1) return values[middle]
    val mean = values[middle - 1].add(values[middle]).divide(TWO, 10, RoundingMode.HALF_UP)
    return mean.setScale(2, RoundingMode.HALF_UP)
}

/**
 * Drives that used at least [ANOMALY_THRESHOLD_PCT] more fuel than the usual value of their
 * route (PRD F13). Only routes with [MIN_ANOMALY_GROUP] usable drives are looked at, and a
 * usual value of `0.00` (a hybrid that drove the route on electricity) is skipped: the
 * percent over zero has no answer. `routeIndex` is the place of the group in [groups], the
 * number the screen describes the route with - never a position (docs/SECURITY.md).
 *
 * Newest drive first (ties by id), at most [ANOMALY_LIMIT] of them. The app says what
 * happened, never why: cold, traffic, roof box and tyre pressure stay a standing hint.
 */
fun fuelAnomalies(groups: List<RouteGroup>): List<FuelAnomaly> {
    val found = mutableListOf<FuelAnomaly>()
    for ((index, group) in groups.withIndex()) {
        val drives = group.trips.mapNotNull { stored -> routeConsumption(stored)?.let { stored to it } }
        if (drives.size < MIN_ANOMALY_GROUP) continue
        val usual = medianConsumption(group) ?: continue
        if (usual.signum() <= 0) continue
        for ((stored, consumption) in drives) {
            val excess = consumption
                .subtract(usual)
                .multiply(HUNDRED)
                .divide(usual, 10, RoundingMode.HALF_UP)
                .setScale(1, RoundingMode.HALF_UP)
            if (excess >= ANOMALY_THRESHOLD_PCT) {
                found += FuelAnomaly(stored, index, consumption, usual, excess)
            }
        }
    }
    val byOrder = compareByDescending<FuelAnomaly> { it.trip.record.trip.start }
        .thenBy { it.trip.record.trip.id }
    return found.sortedWith(byOrder).take(ANOMALY_LIMIT)
}

/** Archive distances arrive as `Double`; a missing or broken one is no basis for a number. */
private fun finiteDistance(stored: StoredTrip): BigDecimal? {
    val kilometres = stored.record.trip.distanceKm
    if (!kilometres.isFinite() || kilometres <= 0.0) return null
    return toBigDecimal(kilometres)
}
