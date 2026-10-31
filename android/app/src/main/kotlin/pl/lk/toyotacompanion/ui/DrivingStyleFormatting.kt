package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/**
 * Description of a route without its position (docs/SPEC.md §19, ADR-008): typical length,
 * how often, at what hour it usually starts and how it is driven. Pure, no `Locale`.
 */
object DrivingStyleFormatting {

    /** Median length of the route's trips with a usable distance, 0,1 km; 0 when none has one. */
    fun typicalKm(group: RouteGroup): BigDecimal {
        val sorted = group.trips.mapNotNull { usableKm(it) }.sorted()
        if (sorted.isEmpty()) return BigDecimal.ZERO.setScale(1)
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            sorted[middle - 1].add(sorted[middle]).divide(BigDecimal(2), 10, RoundingMode.HALF_UP)
        }
        return median.setScale(1, RoundingMode.HALF_UP)
    }

    /** The most frequent full hour of the starts in [zone], e.g. `07:00`; ties go to the earlier hour. */
    fun usualHour(group: RouteGroup, zone: ZoneId): String? {
        val hours = group.trips.groupingBy { it.record.trip.start.atZone(zone).hour }.eachCount()
        val best = hours.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull() ?: return null
        return best.key.toString().padStart(2, '0') + ":00"
    }

    /** Distance-weighted mean of Toyota's eco score on the route, 0,1 point; `null` without scores. */
    fun routeEco(group: RouteGroup): BigDecimal? {
        val scored = group.trips.mapNotNull { s ->
            val score = s.record.scoreGlobal ?: return@mapNotNull null
            usableKm(s)?.let { km -> km to score }
        }
        if (scored.isEmpty()) return null
        val weight = scored.fold(BigDecimal.ZERO) { acc, (km, _) -> acc.add(km) }
        val sum = scored.fold(BigDecimal.ZERO) { acc, (km, score) -> acc.add(km.multiply(BigDecimal(score))) }
        return sum.divide(weight, 10, RoundingMode.HALF_UP).setScale(1, RoundingMode.HALF_UP)
    }

    fun points(value: BigDecimal): String = TripsFormatting.plain(value, 1)

    /** `+32,5 %` — the excess of an anomaly; a negative value keeps its own sign. */
    fun excess(pct: BigDecimal): String =
        (if (pct.signum() < 0) "\u2212" else "+") + TripsFormatting.plain(pct.abs(), 1) + " %"

    /** A distance that can be counted: finite and positive (a broken row must not break the screen). */
    private fun usableKm(stored: StoredTrip): BigDecimal? =
        stored.record.trip.distanceKm.takeIf { it.isFinite() && it > 0.0 }?.let(::toBigDecimal)
}
