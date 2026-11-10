package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
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
    private val MONTHS_NOMINATIVE: List<String>
        get() = if (Lang.isEnglish) {
            listOf(
                "January",
                "February",
                "March",
                "April",
                "May",
                "June",
                "July",
                "August",
                "September",
                "October",
                "November",
                "December",
            )
        } else {
            listOf(
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
        }

    /** Trip cost: `3,68 zł`. */
    fun money(value: BigDecimal): String = number(value, 2) + " zł"

    /** Fuel price: `6,14 zł/l`. */
    fun pricePerLiter(value: BigDecimal): String = number(value, 2) + " zł/l"

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
    fun liters(value: BigDecimal): String = number(value, 2) + " l"

    /** Distance: `12,3 km`. */
    fun kilometers(value: BigDecimal): String = number(value, 1) + " km"

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

    /**
     * The short stamp of the status and the diagnostics screens: `10.11 19:04` in the same day-month shape as the list, with the year
     * (`10.11.2025 19:04`) only when it is not the year of [today]. A plain function of its inputs, so the test fixes the day.
     */
    fun shortStamp(at: Instant, zone: ZoneId, today: LocalDate): String {
        val local = at.atZone(zone)
        if (local.year == today.year) return startDateTime(at, zone)
        return "${two(local.dayOfMonth)}.${two(local.monthValue)}.${local.year} ${two(local.hour)}:${two(local.minute)}"
    }

    /** `29.10` – the date under the x axis of a chart (docs/SPEC.md §15.2). */
    fun dayMonth(instant: Instant, zone: ZoneId): String {
        val date = instant.atZone(zone).toLocalDate()
        return "${two(date.dayOfMonth)}.${two(date.monthValue)}"
    }

    /** A whole count for display, grouped like [number]: `1 024`. */
    fun count(value: Int): String = number(BigDecimal(value), 0)

    /** `123 456` – the odometer as whole kilometres, grouped with a no-break space (docs/SPEC.md §9.3). */
    fun odometer(km: Double): String = number(BigDecimal(km).setScale(0, RoundingMode.HALF_UP), 0)

    /** All month names — the test checks that there are twelve and that they differ. */
    fun monthNames(): List<String> = MONTHS_NOMINATIVE

    /**
     * A number without its unit, e.g. `12,3` (docs/SPEC.md §10.2, §14.4): HALF_UP to
     * [places], a comma, no `Locale`. Shared by the list and the CSV export, so both
     * round the same way.
     */
    internal fun plain(value: BigDecimal, places: Int): String =
        value.setScale(places, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

    /**
     * Estimated CO2 for display (PRD F51, docs/SPEC.md §51): kilograms below 1000 kg (`123,4 kg`), tonnes from 1000 kg (`2,05 t`, two
     * places, HALF_UP). Grouped like [number]; the unit stays with its number on the same line (no-break space).
     */
    fun co2(kg: BigDecimal): String =
        if (kg.abs() >= BigDecimal(1000)) number(kg.divide(BigDecimal(1000)), 2) + "\u00A0t" else number(kg, 1) + "\u00A0kg"

    /** `+0,61` / `-0,20` / `0,00`: a comma, an explicit plus for a positive value (docs/SPEC.md §22-§23). */
    internal fun signed(value: BigDecimal, places: Int): String {
        val text = plain(value, places)
        return if (value.setScale(places, RoundingMode.HALF_UP).signum() > 0) "+$text" else text
    }

    /** `+7,02 zł` / `-7,02 zł` / `0,00 zł`. */
    internal fun signedMoney(value: BigDecimal): String =
        (if (value.setScale(2, RoundingMode.HALF_UP).signum() > 0) "+" else "") + money(value)

    /**
     * The display form of a number, the one every screen and notification uses: the comma of [plain] and a no-break space between
     * the thousands (`4 887,2`), so a long amount reads at a glance and never wraps in the middle. [plain] (no grouping) stays for
     * the CSV export and for text a form field takes back.
     */
    fun number(value: BigDecimal, places: Int): String {
        val text = plain(value, places)
        val sign = if (text.startsWith("-")) "-" else ""
        val digits = text.removePrefix("-")
        val whole = digits.substringBefore(',')
        val rest = digits.substring(whole.length)
        val grouped = whole.reversed().chunked(3).joinToString("\u00A0").reversed()
        return sign + grouped + rest
    }

    private fun two(value: Int): String = if (value < 10) "0$value" else value.toString()
}
