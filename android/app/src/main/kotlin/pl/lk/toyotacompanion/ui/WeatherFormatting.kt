package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import pl.lk.toyotacompanion.core.domain.WeatherBucketStats

/** Polish text of a weather bucket (PRD F15, docs/SPEC.md §23). Pure and independent of `Locale`. */
object WeatherFormatting {
    /** `trasy: 24 · 312,4 km · 4,38 l/100 km · 22,10 zł/100 km`; the price part is "brak ceny" without a price. */
    fun bucketLine(bucket: WeatherBucketStats): String =
        listOfNotNull(
            Texts.statsTrips(bucket.trips),
            TripsFormatting.plain(bucket.distanceKm, 1) + " km",
            bucket.lPer100Km?.let { Texts.trafficConsumption(TripsFormatting.plain(it, 2)) },
            bucket.costPer100Km?.let { Texts.trafficCost(TripsFormatting.money(it)) } ?: Texts.NO_PRICE,
        ).joinToString(Texts.SEPARATOR)

    /**
     * The second line: the gap to the reference, the share of electric driving (it differs between buckets and
     * explains part of any gap) and the "too few trips" warning. `null` when there is nothing to add.
     */
    fun bucketNote(bucket: WeatherBucketStats): String? {
        val parts = ArrayList<String>(3)
        bucket.deltaLPer100Km?.let { parts += Texts.weatherDelta(signed(it)) }
        val ev = evShare(bucket)
        if (ev != null) parts += Texts.weatherEv(ev)
        if (bucket.tooFewTrips) parts += Texts.WEATHER_TOO_FEW
        return if (parts.isEmpty()) null else parts.joinToString(Texts.SEPARATOR)
    }

    /** Electric km over all km of the bucket, whole percent; `null` without distance. */
    fun evShare(bucket: WeatherBucketStats): String? {
        if (bucket.distanceKm.signum() <= 0) return null
        val percent = bucket.evKm.multiply(BigDecimal(100)).divide(bucket.distanceKm, 0, RoundingMode.HALF_UP)
        return percent.toPlainString() + " %"
    }

    /** `+0,61` / `-0,20` / `0,00`: two decimals, a comma, an explicit plus. */
    fun signed(value: BigDecimal): String {
        val text = TripsFormatting.plain(value, 2)
        return if (value.signum() > 0) "+$text" else text
    }
}
