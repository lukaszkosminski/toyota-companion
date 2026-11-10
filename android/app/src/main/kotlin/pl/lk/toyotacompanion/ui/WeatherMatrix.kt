package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import pl.lk.toyotacompanion.core.domain.TrafficClass
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.DayWeather
import pl.lk.toyotacompanion.core.domain.TemperatureBucket
import pl.lk.toyotacompanion.core.domain.WEATHER_MIN_TRIPS_PER_BUCKET
import pl.lk.toyotacompanion.core.domain.temperatureBucket
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/**
 * One cell of the speed × temperature table: the trips of a speed band that fell on days of one temperature bucket
 * (docs/SPEC.md §28). [lPer100] is Σ fuel / Σ km; [enough] is false below [WEATHER_MIN_TRIPS_PER_BUCKET] trips, and then
 * the screen shows the count but no figure to conclude from.
 */
data class MatrixCell(
    val trips: Int,
    val distanceKm: BigDecimal,
    val lPer100: BigDecimal,
    val evSharePct: BigDecimal,
    val enough: Boolean,
)

/** One speed band with a cell per temperature bucket; a bucket without trips has no cell. */
data class MatrixRow(val speed: TrafficClass, val cells: Map<TemperatureBucket, MatrixCell>) {
    /**
     * Frost against mild in the same band, l/100 km, only when both cells are large enough: the winter surcharge of driving
     * at that speed. `null` otherwise - the table does not guess a difference from a handful of trips.
     */
    fun frostSurcharge(): BigDecimal? {
        val frost = cells[TemperatureBucket.FROST] ?: return null
        val mild = cells[TemperatureBucket.MILD] ?: return null
        if (!frost.enough || !mild.enough) return null
        return frost.lPer100.subtract(mild.lPer100)
    }
}

/**
 * Trips by speed band (the bands of §22) and by the temperature of their day (the buckets of §23). Only trips the speed
 * bands count (§22) and that have a day with a mean temperature in [weather] (by trip id, §23) enter. Bands without any
 * such trip are left out. Pure; the domain gets no coordinate.
 */
fun speedWeatherMatrix(
    trips: List<StoredTrip>,
    weather: Map<String, DayWeather>,
    minTrips: Int = WEATHER_MIN_TRIPS_PER_BUCKET,
): List<MatrixRow> {
    data class Key(val speed: TrafficClass, val bucket: TemperatureBucket)
    val groups = HashMap<Key, MutableList<StoredTrip>>()
    for (stored in trips) {
        val speed = usable(stored)?.let { bandOf(it) } ?: continue
        val bucket = temperatureBucket(weather[stored.record.id]?.tempMean) ?: continue
        groups.getOrPut(Key(speed, bucket)) { ArrayList() } += stored
    }
    return TrafficClass.entries.mapNotNull { speed ->
        val cells = TemperatureBucket.entries.mapNotNull { bucket ->
            val group = groups[Key(speed, bucket)] ?: return@mapNotNull null
            var km = BigDecimal.ZERO
            var fuel = BigDecimal.ZERO
            var electric = BigDecimal.ZERO
            for (stored in group) {
                val trip = stored.record.trip
                km = km.add(toBigDecimal(trip.distanceKm))
                fuel = fuel.add(trip.fuelL)
                val ev = trip.evDistanceKm
                if (ev != null && ev.isFinite() && ev > 0.0) electric = electric.add(toBigDecimal(minOf(ev, trip.distanceKm)))
            }
            bucket to MatrixCell(
                trips = group.size,
                distanceKm = km.setScale(1, RoundingMode.HALF_UP),
                lPer100 = fuel.multiply(BigDecimal(100)).divide(km, 2, RoundingMode.HALF_UP),
                evSharePct = electric.multiply(BigDecimal(100)).divide(km, 0, RoundingMode.HALF_UP),
                enough = group.size >= minTrips,
            )
        }.toMap()
        if (cells.isEmpty()) null else MatrixRow(speed, cells)
    }
}
