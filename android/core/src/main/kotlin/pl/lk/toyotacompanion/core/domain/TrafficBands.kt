package pl.lk.toyotacompanion.core.domain

/** Upper bounds (km/h, exclusive) of [TrafficClass.SLOW], [TrafficClass.CITY] and [TrafficClass.FLOWING] (PRD F19, docs/SPEC.md §22). */
const val TRAFFIC_SLOW_BELOW = 15.0
const val TRAFFIC_CITY_BELOW = 25.0
const val TRAFFIC_FLOWING_BELOW = 40.0

/** Speed band of a trip - an indirect measure of traffic, not a reading of it (docs/SPEC.md §22). */
enum class TrafficClass { SLOW, CITY, FLOWING, HIGHWAY }

/** The band of an average speed in km/h; the lower bound of each band belongs to it. Moved from `app/ui` (PRD F52), same behaviour. */
fun trafficBandOf(speedKmh: Double): TrafficClass = when {
    speedKmh < TRAFFIC_SLOW_BELOW -> TrafficClass.SLOW
    speedKmh < TRAFFIC_CITY_BELOW -> TrafficClass.CITY
    speedKmh < TRAFFIC_FLOWING_BELOW -> TrafficClass.FLOWING
    else -> TrafficClass.HIGHWAY
}
