package pl.lk.toyotacompanion.ui

import java.math.BigDecimal

/** Polish text of the consumption card (docs/SPEC.md §24). Pure and independent of `Locale`. */
object ConsumptionFormatting {
    private val MONTHS_SHORT = listOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")

    /** `paź`, with the year's last two digits for January so a chart over a year boundary reads: `sty 27`. */
    fun monthShort(month: MonthKey): String {
        val name = MONTHS_SHORT[month.month - 1]
        return if (month.month == 1) name + " " + (month.year % 100).toString().padStart(2, '0') else name
    }

    /** `Średnio z całego okresu: 4,12 l/100 km (2 412,3 km)`; the km part without a thousands separator quirk. */
    fun overallLine(view: ConsumptionView): String =
        Texts.consumptionOverall(
            TripsFormatting.plain(requireNotNull(view.overall), 2),
            TripsFormatting.plain(view.overallKm, 0),
        )

    /** `↗ rosnące (+8,2 %)`, `↘ malejące (-6,0 %)` or `→ stabilne`; `null` without a trend. */
    fun trendLine(view: ConsumptionView): String? {
        val trend = view.trend ?: return null
        val change = view.changePct ?: return null
        return when (trend) {
            ConsumptionTrend.RISING -> Texts.consumptionRising(signed(change))
            ConsumptionTrend.FALLING -> Texts.consumptionFalling(signed(change))
            ConsumptionTrend.STEADY -> Texts.consumptionSteady(signed(change))
        }
    }

    /**
     * Where the bars start: 70 % of the lowest month, rounded down to 0.1 l/100 km, so that the differences between
     * months are visible; the card says the axis does not start at zero. A single month (or all equal) starts at 0.
     */
    fun axisBase(months: List<MonthConsumption>): BigDecimal {
        if (months.size < 2) return BigDecimal.ZERO
        val low = months.minOf { it.lPer100 }
        if (low == months.maxOf { it.lPer100 }) return BigDecimal.ZERO
        return low.multiply(BigDecimal("0.7")).setScale(1, java.math.RoundingMode.DOWN)
    }

    /** Height of a bar as a share (0.08–1.0) of the tallest one on the axis starting at [base]. */
    fun barShare(value: BigDecimal, top: BigDecimal, base: BigDecimal): Float {
        val span = top.subtract(base)
        if (span.signum() <= 0) return 1f
        return value.subtract(base).toFloat().div(span.toFloat()).coerceIn(0.08f, 1f)
    }

    private fun signed(value: BigDecimal): String {
        val text = TripsFormatting.plain(value, 1)
        return if (value.signum() > 0) "+$text" else text
    }

    /** Money of a month on the bar label: whole zloty, `–` without a price. */
    fun costLabel(cost: MonthCost): String = cost.costPln?.let { TripsFormatting.plain(it, 0) } ?: "–"

    /** `października 2026`-free line of a month: `paź: 217,90 zł · 776,4 km · 28,07 zł/100 km` (+ the unpriced count). */
    fun costLine(cost: MonthCost): String = listOfNotNull(
        monthShort(cost.month) + ": " + (cost.costPln?.let { TripsFormatting.money(it) } ?: Texts.NO_PRICE),
        TripsFormatting.plain(cost.distanceKm, 1) + " km",
        cost.costPer100?.let { Texts.trafficCost(TripsFormatting.money(it)) },
        if (cost.tripsWithoutCost > 0) Texts.tripsWithoutCost(cost.tripsWithoutCost) else null,
    ).joinToString(Texts.SEPARATOR)

    /** `Razem od kwi 2026 do lis 2026: 1 182,89 zł · bez ceny: 12`; `null` without a single price. */
    fun totalCostLine(view: ConsumptionView): String? = view.totalCost?.let {
        val period = view.periodFrom?.let { from -> view.periodTo?.let { to -> monthWithYear(from) + " – " + monthWithYear(to) } }
        Texts.consumptionTotalCost(TripsFormatting.money(it), period) +
            if (view.totalTripsWithoutCost > 0) Texts.SEPARATOR + Texts.tripsWithoutCost(view.totalTripsWithoutCost) else ""
    }

    /** `kwi 2026`: the short month with its year, for a period that may span years. */
    fun monthWithYear(month: MonthKey): String = MONTHS_SHORT[month.month - 1] + " " + month.year
}
