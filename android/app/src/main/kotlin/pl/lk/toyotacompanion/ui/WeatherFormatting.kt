package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import pl.lk.toyotacompanion.core.domain.WeatherBucketStats

/** Polish text of a weather bucket (PRD F15, docs/SPEC.md §23). Pure and independent of `Locale`. */
object WeatherFormatting {
    /**
     * `24 trasy · 312,4 km`: the short line under the name of a bucket (docs/SPEC.md §23). The consumption stands on the right as the big
     * number and the price per 100 km is in [bucketDetails], so this line fits a 360 dp screen without wrapping.
     */
    fun bucketLine(bucket: WeatherBucketStats): String =
        listOf(Texts.tripsShort(bucket.trips), TripsFormatting.number(bucket.distanceKm, 1) + " km").joinToString(Texts.SEPARATOR)

    /** `22,10 zł/100 km`, or "brak ceny" without a price (never 0 zł). */
    fun bucketCost(bucket: WeatherBucketStats): String =
        bucket.costPer100Km?.let { Texts.trafficCost(TripsFormatting.money(it)) } ?: Texts.NO_PRICE

    /** The helper line of a bucket: [bucketCost] first, then [bucketNote] when there is one; it wraps over the full width of the card. */
    fun bucketDetails(bucket: WeatherBucketStats): String = listOfNotNull(bucketCost(bucket), bucketNote(bucket)).joinToString(Texts.SEPARATOR)

    /**
     * The second line: the gap to the reference, the share of electric driving (it differs between buckets and
     * explains part of any gap) and the "too few trips" warning. `null` when there is nothing to add.
     */
    fun bucketNote(bucket: WeatherBucketStats): String? {
        val parts = ArrayList<String>(3)
        bucket.deltaLPer100Km?.let { parts += Texts.weatherDelta(TripsFormatting.signed(it, 2)) }
        val ev = evShare(bucket)
        if (ev != null) parts += Texts.weatherEv(ev)
        bucket.windMaxKmh?.let { parts += Texts.weatherWind(TripsFormatting.number(it, 0)) }
        if (bucket.tooFewTrips) parts += Texts.WEATHER_TOO_FEW
        return if (parts.isEmpty()) null else parts.joinToString(Texts.SEPARATOR)
    }

    /** Electric km over all km of the bucket, whole percent; `null` without distance. */
    fun evShare(bucket: WeatherBucketStats): String? {
        if (bucket.distanceKm.signum() <= 0) return null
        // Toyota rounds meters, so the electric part can exceed the whole: never over 100 % (docs/SPEC.md §16).
        val electric = bucket.evKm.min(bucket.distanceKm)
        val percent = electric.multiply(BigDecimal(100)).divide(bucket.distanceKm, 0, RoundingMode.HALF_UP)
        return percent.toPlainString() + " %"
    }

    /** One cell: `4,92 l (12)`, or `7 tras – za mało` below the minimum; `–` without trips. */
    fun cellText(cell: MatrixCell?): String = when {
        cell == null -> "–"
        !cell.enough -> Texts.matrixTooFew(cell.trips)
        else -> Texts.matrixCell(TripsFormatting.number(cell.lPer100, 2), cell.trips)
    }

    /** `mróz 5,41 l (12) · chłodno 4,90 l (30) · łagodnie – · ciepło 3,8 l (9)` for one speed band. */
    fun rowText(row: MatrixRow): String =
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.entries.joinToString(Texts.SEPARATOR) {
            Texts.matrixBucketShort(it) + " " + cellText(row.cells[it])
        }

    /** `Zimą przy tej prędkości: +0,81 l/100 km (mróz wobec łagodnie)`; `null` when either side is too small. */
    fun frostLine(row: MatrixRow): String? = row.frostSurcharge()?.let { Texts.matrixFrost(TripsFormatting.signed(it, 2)) }
}
