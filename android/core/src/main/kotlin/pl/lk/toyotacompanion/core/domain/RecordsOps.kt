package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Minimum trip distance for the "best economy of a trip" record (R4). Mirrors Python `MIN_ECONOMY_TRIP_KM`. */
val MIN_ECONOMY_TRIP_KM: BigDecimal = BigDecimal("50")

/** Minimum trip distance for the "best EV share of a trip" record (R3). Mirrors Python `MIN_EV_SHARE_TRIP_KM`. */
val MIN_EV_SHARE_TRIP_KM: BigDecimal = BigDecimal("20")

/** Minimum monthly distance for the "best economy of a month" record (R6). Mirrors Python `MIN_ECONOMY_MONTH_KM`. */
val MIN_ECONOMY_MONTH_KM: BigDecimal = BigDecimal("500")

private val HUNDRED = BigDecimal(100)
private val ZERO = BigDecimal.ZERO

private val MONTH_NAMES_PL = arrayOf(
    "", "styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec",
    "lipiec", "sierpień", "wrzesień", "październik", "listopad", "grudzień",
)

private fun localDate(moment: java.time.Instant, zone: ZoneId): LocalDate =
    moment.atZone(zone).toLocalDate()

/** `fuel / distance * 100` rounded to 0.01 l/100 km; `null` at zero distance. Mirrors Python `_trip_economy`. */
private fun tripEconomy(trip: Trip): BigDecimal? {
    val distance = toBigDecimal(trip.distanceKm)
    if (distance <= ZERO) return null
    return roundConsumption(trip.fuelL.multiply(HUNDRED).divideLikePythonDecimal(distance))
}

/** `ev / distance * 100` rounded to 0.1 %, clipped at 100; `null` without EV data or at zero distance. */
private fun evShare(trip: Trip): BigDecimal? {
    val distance = toBigDecimal(trip.distanceKm)
    val evKm = trip.evDistanceKm ?: return null
    if (distance <= ZERO) return null
    val ev = toBigDecimal(evKm)
    return roundPercent(ev.min(distance).multiply(HUNDRED).divideLikePythonDecimal(distance))
}

/**
 * All personal records from the trip archive (PRD F62, docs/SPEC.md §62), read-only.
 * Mirrors Python `compute_records`:
 * - R1 `longest_trip`: the longest trip in km.
 * - R2 `longest_ev_trip`: the longest EV distance of one trip in km (needs `evDistanceKm`).
 * - R3 `best_ev_share`: the highest EV share % of a trip of at least 20 km.
 * - R4 `best_economy_trip`: the lowest l/100 km of a trip of at least 50 km.
 * - R5 `most_km_month`: the most km driven in one calendar month (local).
 * - R6 `best_economy_month`: the lowest pooled l/100 km of a month with at least 500 km.
 *
 * A record is omitted when no trip or month qualifies. [Records.empty] is true when all were omitted.
 */
fun computeRecords(trips: List<Trip>, zone: ZoneId): Records {
    if (trips.isEmpty()) return Records(emptyList(), empty = true)
    val sorted = trips.sortedBy { it.start }
    val items = mutableListOf<Record>()

    // R1 – longest trip.
    val longest = sorted.maxBy { toBigDecimal(it.distanceKm) }
    items += Record(
        recordId = "longest_trip",
        value = roundDistance(toBigDecimal(longest.distanceKm)),
        unit = "km",
        date = localDate(longest.start, zone),
    )

    // R2 – longest EV distance of one trip.
    val withEv = sorted.filter { it.evDistanceKm != null }
    if (withEv.isNotEmpty()) {
        val longestEv = withEv.maxBy { toBigDecimal(requireNotNull(it.evDistanceKm)) }
        items += Record(
            recordId = "longest_ev_trip",
            value = roundDistance(toBigDecimal(requireNotNull(longestEv.evDistanceKm))),
            unit = "km",
            date = localDate(longestEv.start, zone),
        )
    }

    // R3 – best EV share of a trip of at least 20 km.
    val shareCandidates = sorted.mapNotNull { t ->
        val share = evShare(t)
        if (share != null && toBigDecimal(t.distanceKm) >= MIN_EV_SHARE_TRIP_KM) t to share else null
    }
    if (shareCandidates.isNotEmpty()) {
        val (bestTrip, bestShare) = shareCandidates.maxBy { it.second }
        items += Record(
            recordId = "best_ev_share",
            value = bestShare,
            unit = "%",
            date = localDate(bestTrip.start, zone),
            context = "${roundDistance(toBigDecimal(bestTrip.distanceKm))} km",
        )
    }

    // R4 – best economy of a single trip of at least 50 km.
    val econCandidates = sorted.mapNotNull { t ->
        val econ = tripEconomy(t)
        if (econ != null && toBigDecimal(t.distanceKm) >= MIN_ECONOMY_TRIP_KM) t to econ else null
    }
    if (econCandidates.isNotEmpty()) {
        val (bestTrip, bestEcon) = econCandidates.minBy { it.second }
        items += Record(
            recordId = "best_economy_trip",
            value = bestEcon,
            unit = "l/100 km",
            date = localDate(bestTrip.start, zone),
            context = "${roundDistance(toBigDecimal(bestTrip.distanceKm))} km",
        )
    }

    // Monthly aggregates (by local month of the trip start).
    val months = sorted.groupBy { localDate(it.start, zone).let { d -> d.year to d.monthValue } }

    // R5 – most km in one month.
    if (months.isNotEmpty()) {
        val bestKey = months.maxBy { (_, members) -> members.fold(ZERO) { acc, t -> acc.add(toBigDecimal(t.distanceKm)) } }.key
        val monthKm = roundDistance(months.getValue(bestKey).fold(ZERO) { acc, t -> acc.add(toBigDecimal(t.distanceKm)) })
        items += Record(
            recordId = "most_km_month",
            value = monthKm,
            unit = "km",
            date = LocalDate.of(bestKey.first, bestKey.second, 1),
            context = "${MONTH_NAMES_PL[bestKey.second]} ${bestKey.first}",
        )
    }

    // R6 – best pooled economy of a month with at least 500 km.
    val econMonths = months.mapNotNull { (key, members) ->
        val distance = members.fold(ZERO) { acc, t -> acc.add(toBigDecimal(t.distanceKm)) }
        if (distance < MIN_ECONOMY_MONTH_KM) return@mapNotNull null
        val fuel = members.fold(ZERO) { acc, t -> acc.add(t.fuelL) }
        key to roundConsumption(fuel.multiply(HUNDRED).divideLikePythonDecimal(distance))
    }
    if (econMonths.isNotEmpty()) {
        val (bestKey, bestEcon) = econMonths.minBy { it.second }
        items += Record(
            recordId = "best_economy_month",
            value = bestEcon,
            unit = "l/100 km",
            date = LocalDate.of(bestKey.first, bestKey.second, 1),
            context = "${MONTH_NAMES_PL[bestKey.second]} ${bestKey.first}",
        )
    }

    return Records(items, empty = items.isEmpty())
}
