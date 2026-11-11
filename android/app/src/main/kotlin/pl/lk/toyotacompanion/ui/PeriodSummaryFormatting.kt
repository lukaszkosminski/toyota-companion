package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import pl.lk.toyotacompanion.core.domain.PeriodTotals
import pl.lk.toyotacompanion.core.domain.SummaryPeriod
import pl.lk.toyotacompanion.core.domain.SummaryWindow
import pl.lk.toyotacompanion.core.domain.percentChangeOf

/** The two periods the summary compares: the one it reports and the one right before it (docs/SPEC.md §54). */
data class PeriodSummary(val window: SummaryWindow, val now: PeriodTotals, val previous: PeriodTotals)

/**
 * The text of the period summary notification (PRD F54, docs/SPEC.md §54). Pure and independent of `Locale`. The collapsed notification has a
 * title and one short line (km, cost, litres per 100 km, share on battery, all money in whole zloty); the expanded one adds the split of the cost
 * and the comparison with the previous period. Only aggregated numbers: no VIN, no position, no place name.
 */
object PeriodSummaryFormatting {
    /** `Tydzień 2–8.11` or `Październik 2026`. */
    fun title(window: SummaryWindow): String = when (window.period) {
        SummaryPeriod.WEEK -> Texts.periodWeekTitle(dateRange(window.from, window.until.minusDays(1)))
        SummaryPeriod.MONTH -> TripsFormatting.monthLabel(MonthKey(window.from.year, window.from.monthValue))
            .replaceFirstChar { it.uppercaseChar() }
    }

    /** `412 km · 58 zł · 4,6 l/100 km · 38 % na prądzie`; the litres and the share are left out when there is no value. */
    fun body(summary: PeriodSummary): String {
        val now = summary.now
        return listOfNotNull(
            TripsFormatting.number(now.distanceKm, 0) + " km",
            wholeCost(now) + " zł",
            now.lPer100Km?.let { TripsFormatting.number(it, 1) + " l/100 km" },
            now.evSharePct?.let { Texts.periodEvShare(TripsFormatting.number(it, 0)) },
        ).joinToString(Texts.SEPARATOR)
    }

    /** The body and, under it, the split of the cost and the comparison with the previous period (the expanded view). */
    fun expanded(summary: PeriodSummary): String {
        val now = summary.now
        val fuel = now.fuelCostPln?.let { wholeZloty(it) + " zł" } ?: Texts.NO_PRICE
        val lines = mutableListOf(body(summary), Texts.periodBreakdown(fuel, wholeZloty(now.expensesPln) + " zł"))
        versus(summary)?.let { lines += it }
        return lines.joinToString("\n")
    }

    /** `Wobec poprzedniego tygodnia: km +12, koszt -5 %`; `null` when the previous period has nothing to compare with. */
    fun versus(summary: PeriodSummary): String? {
        if (summary.previous.isEmpty) return null
        val parts = listOfNotNull(
            percentChangeOf(summary.now.distanceKm, summary.previous.distanceKm)?.let { "km ${TripsFormatting.signed(it, 0)} %" },
            percentChangeOf(wholeCostValue(summary.now), wholeCostValue(summary.previous))?.let {
                "${Texts.PERIOD_COST_WORD} ${TripsFormatting.signed(it, 0)} %"
            },
        )
        if (parts.isEmpty()) return null
        return Texts.periodVersus(summary.window.period == SummaryPeriod.WEEK, parts.joinToString(", "))
    }

    /** Whole zloty, HALF_UP: the body shows no grosze. */
    fun wholeZloty(value: BigDecimal): String = TripsFormatting.number(wholeValue(value), 0)

    /** The cost of a period in whole zloty: the fuel and the expenses are rounded one by one, so the total is the sum of the two parts shown. */
    fun wholeCost(totals: PeriodTotals): String = TripsFormatting.number(wholeCostValue(totals), 0)

    private fun wholeCostValue(totals: PeriodTotals): BigDecimal =
        wholeValue(totals.fuelCostPln ?: BigDecimal.ZERO).add(wholeValue(totals.expensesPln))

    private fun wholeValue(value: BigDecimal): BigDecimal = value.setScale(0, RoundingMode.HALF_UP)

    /** `2–8.11` for the days of the week; the first day and the last day, each with its month when they differ. */
    internal fun dateRange(first: LocalDate, last: LocalDate): String =
        if (first.monthValue == last.monthValue) {
            "${first.dayOfMonth}–${last.dayOfMonth}.${last.monthValue}"
        } else {
            "${first.dayOfMonth}.${first.monthValue}–${last.dayOfMonth}.${last.monthValue}"
        }
}
