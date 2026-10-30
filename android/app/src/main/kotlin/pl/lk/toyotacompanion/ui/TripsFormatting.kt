package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/**
 * Formatting of the numbers and dates of the trips list (docs/SPEC.md §10.2).
 *
 * Deliberately independent of `Locale.getDefault()`: the value is rounded HALF_UP,
 * written by `toPlainString()` (always a dot) and the dot is swapped for a comma.
 * `String.format("%.2f")` and `%02d` are forbidden — the device locale changes both
 * the separator and the digits.
 */
object TripsFormatting {

    /** Month names in nominative case (docs/SPEC.md §10.2) — own table, no `TextStyle`, no `Locale`. */
    private val MONTHS_NOMINATIVE = listOf(
        "styczeń",
        "luty",
        "marzec",
        "kwiecień",
        "maj",
        "czerwiec",
        "lipiec",
        "sierpień",
        "wrzesień",
        "październik",
        "listopad",
        "grudzień",
    )

    /** Trip cost: `3,68 zł`. */
    fun money(value: BigDecimal): String = fixed(value, 2) + " zł"

    /** Fuel price: `6,14 zł/l`. */
    fun pricePerLiter(value: BigDecimal): String = fixed(value, 2) + " zł/l"

    /**
     * Line under the cost of a trip (docs/SPEC.md §10.3): `6,14 zł/l`, or `6,14 zł/l · cena
     * domyślna` when the price came from the configured default rather than a refuel. Null
     * when the trip has no price to show — the row then says just "brak ceny".
     */
    fun priceLine(trip: StoredTrip): String? {
        val cost = trip.cost ?: return null
        // A price without an amount (or a NONE cost) would contradict the "brak ceny" above it.
        if (hasNoCost(cost)) return null
        val price = cost.pricePerLiter ?: return null
        val shown = pricePerLiter(price)
        return if (cost.source == CostSource.DEFAULT) shown + Texts.SEPARATOR + Texts.DEFAULT_PRICE_NOTE else shown
    }

    /** Fuel amount: `0,60 l`. */
    fun liters(value: BigDecimal): String = fixed(value, 2) + " l"

    /** Distance: `12,3 km`. */
    fun kilometers(value: BigDecimal): String = fixed(value, 1) + " km"

    /** Distance from the `Double` field — via `toBigDecimal`, no binary float summation. */
    fun kilometers(value: Double): String = kilometers(toBigDecimal(value))

    /** Trip start as `29.10 14:05` in the given zone (docs/SPEC.md §10.2). */
    fun startDateTime(start: Instant, zone: ZoneId): String {
        val local = start.atZone(zone)
        return "${two(local.dayOfMonth)}.${two(local.monthValue)} ${two(local.hour)}:${two(local.minute)}"
    }

    /**
     * Month header: `październik 2026` (docs/SPEC.md §10.2). No zone parameter — the month
     * is already computed in the zone by [groupTripsByMonth].
     */
    fun monthLabel(month: MonthKey): String = "${MONTHS_NOMINATIVE[month.month - 1]} ${month.year}"

    /** All month names — the test checks that there are twelve and that they differ. */
    fun monthNames(): List<String> = MONTHS_NOMINATIVE

    private fun fixed(value: BigDecimal, places: Int): String =
        value.setScale(places, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

    private fun two(value: Int): String = if (value < 10) "0$value" else value.toString()
}
