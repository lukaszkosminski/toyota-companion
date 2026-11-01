package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/** Shortest trip that counts: below it the average speed says more about the car park than the road. */
const val TRAFFIC_MIN_KM = 2.0

/** Upper bounds (km/h, exclusive) of [TrafficClass.SLOW], [TrafficClass.CITY] and [TrafficClass.FLOWING]. */
const val TRAFFIC_SLOW_BELOW = 15.0
const val TRAFFIC_CITY_BELOW = 25.0
const val TRAFFIC_FLOWING_BELOW = 40.0

/** How many trips both ends of the comparison need before the surcharge is stated. */
const val TRAFFIC_MIN_COMPARE_TRIPS = 10

/** Speed band of a trip - an indirect measure of traffic, not a reading of it (docs/SPEC.md §22). */
enum class TrafficClass { SLOW, CITY, FLOWING, HIGHWAY }

/** One row of the "Tempo jazdy a spalanie" summary. */
data class TrafficSummary(
    val trafficClass: TrafficClass,
    val trips: Int,
    val distanceKm: BigDecimal,
    /** Σ fuel / Σ km × 100, 0.01 l; every trip in the class has fuel and distance. */
    val litresPer100: BigDecimal,
    /** Σ cost / Σ km of the priced trips × 100, `null` when no trip of the class has a price. */
    val costPer100: BigDecimal?,
    /** Σ electric km (each trip cut to its own distance) / Σ km of the class, whole percent; `null` without any. */
    val evSharePct: BigDecimal?,
)

/** The surcharge of slow driving over flowing driving, only when both bands are large enough. */
data class SlowSurcharge(val litresPer100: BigDecimal, val costPer100: BigDecimal?)

/** The band of an average speed in km/h; the lower bound of each band belongs to it. */
fun trafficClassOf(speedKmh: Double): TrafficClass {
    require(speedKmh.isFinite() && speedKmh > 0.0) { "A speed band needs a positive, finite speed" }
    return bandOf(speedKmh)
}

private fun bandOf(speedKmh: Double): TrafficClass = when {
    speedKmh < TRAFFIC_SLOW_BELOW -> TrafficClass.SLOW
    speedKmh < TRAFFIC_CITY_BELOW -> TrafficClass.CITY
    speedKmh < TRAFFIC_FLOWING_BELOW -> TrafficClass.FLOWING
    else -> TrafficClass.HIGHWAY
}

private fun usable(stored: StoredTrip): Double? {
    val speed = stored.record.averageSpeed ?: return null
    val km = stored.record.trip.distanceKm
    val fuel = stored.record.trip.fuelL
    if (!speed.isFinite() || speed <= 0.0) return null
    if (!km.isFinite() || km < TRAFFIC_MIN_KM) return null
    if (fuel.signum() < 0) return null
    return speed
}

/**
 * Trips by the average speed Toyota reports (PRD F19, docs/SPEC.md §22). Trips without a speed, a distance of at
 * least [TRAFFIC_MIN_KM] or with a negative fuel reading are left out; a band without trips is not listed.
 */
fun trafficSummaries(trips: List<StoredTrip>): List<TrafficSummary> {
    val byClass = trips.mapNotNull { stored -> usable(stored)?.let { bandOf(it) to stored } }
        .groupBy({ it.first }, { it.second })
    val rows = ArrayList<TrafficSummary>(TrafficClass.entries.size)
    for (cls in TrafficClass.entries) {
        val group = byClass[cls] ?: continue
        var km = BigDecimal.ZERO
        var fuel = BigDecimal.ZERO
        for (stored in group) {
            km = km.add(toBigDecimal(stored.record.trip.distanceKm))
            fuel = fuel.add(stored.record.trip.fuelL)
        }
        var electricKm = BigDecimal.ZERO
        for (stored in group) {
            val ev = stored.record.trip.evDistanceKm ?: continue
            if (ev.isFinite() && ev > 0.0) electricKm = electricKm.add(toBigDecimal(minOf(ev, stored.record.trip.distanceKm)))
        }
        var pricedKm = BigDecimal.ZERO
        var cost: BigDecimal? = null
        for (stored in group) {
            val value = stored.cost?.costPln ?: continue
            if (hasNoCost(stored.cost)) continue
            pricedKm = pricedKm.add(toBigDecimal(stored.record.trip.distanceKm))
            cost = cost?.add(value) ?: value
        }
        rows += TrafficSummary(
            trafficClass = cls,
            trips = group.size,
            distanceKm = km.setScale(1, RoundingMode.HALF_UP),
            litresPer100 = fuel.multiply(HUNDRED).divide(km, 2, RoundingMode.HALF_UP),
            costPer100 = cost?.multiply(HUNDRED)?.divide(pricedKm, 2, RoundingMode.HALF_UP),
            evSharePct = if (km.signum() > 0) electricKm.multiply(HUNDRED).divide(km, 0, RoundingMode.HALF_UP) else null,
        )
    }
    return rows
}

/** Trips the bands do not count: no speed, under [TRAFFIC_MIN_KM] or a broken reading (docs/SPEC.md §22). */
fun trafficLeftOut(trips: List<StoredTrip>): Int = trips.count { usable(it) == null }

/** [TrafficClass.SLOW] minus [TrafficClass.FLOWING], `null` unless both have [TRAFFIC_MIN_COMPARE_TRIPS] trips. */
fun slowSurcharge(rows: List<TrafficSummary>): SlowSurcharge? {
    val slow = rows.firstOrNull { it.trafficClass == TrafficClass.SLOW }
    val flowing = rows.firstOrNull { it.trafficClass == TrafficClass.FLOWING }
    if (slow == null || flowing == null) return null
    if (slow.trips < TRAFFIC_MIN_COMPARE_TRIPS || flowing.trips < TRAFFIC_MIN_COMPARE_TRIPS) return null
    val cost = if (slow.costPer100 != null && flowing.costPer100 != null) slow.costPer100.subtract(flowing.costPer100) else null
    return SlowSurcharge(slow.litresPer100.subtract(flowing.litresPer100), cost)
}

private val HUNDRED = BigDecimal(100)
