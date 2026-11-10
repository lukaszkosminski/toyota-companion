package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Each step of the three months must rise by at least this share of the month before. */
val ALERT_MIN_STEP_PCT: BigDecimal = BigDecimal(3)

/** The whole rise over the three months must reach this share of the first one. */
val ALERT_MIN_TOTAL_PCT: BigDecimal = BigDecimal(8)

/** Months of the alert: March to October. In winter consumption rises on its own and an alert would only repeat the season. */
val ALERT_MONTHS: IntRange = 3..10

/** Three completed months in a row with rising consumption (docs/SPEC.md §31). [key] is the last month, `YYYY-MM`. */
data class RisingAlert(val months: List<MonthConsumption>, val totalPct: BigDecimal) {
    val key: String get() = months.last().month.let { it.year.toString() + "-" + it.month.toString().padStart(2, '0') }
}

/**
 * The alert for the bars of [view], or `null`. The three newest completed months (the month of [today] is not finished and
 * is left out) must be consecutive calendar months, rise by at least [ALERT_MIN_STEP_PCT] each step and [ALERT_MIN_TOTAL_PCT]
 * in all, and the last of them must fall in [ALERT_MONTHS]. Only a hint to look at the tyres and the like; it is not a diagnosis.
 */
fun risingConsumptionAlert(view: ConsumptionView, today: LocalDate): RisingAlert? {
    val current = MonthKey(today.year, today.monthValue)
    val completed = view.months.filter { it.month < current }
    if (completed.size < 3) return null
    val last = completed.takeLast(3)
    val ordinals = last.map { it.month.year * 12 + it.month.month }
    if (ordinals[1] != ordinals[0] + 1 || ordinals[2] != ordinals[1] + 1) return null
    if (last.last().month.month !in ALERT_MONTHS) return null
    fun rise(from: BigDecimal, to: BigDecimal): BigDecimal =
        if (from.signum() <= 0) BigDecimal.ZERO else to.subtract(from).multiply(BigDecimal(100)).divide(from, 6, RoundingMode.HALF_UP)
    if (rise(last[0].lPer100, last[1].lPer100) < ALERT_MIN_STEP_PCT) return null
    if (rise(last[1].lPer100, last[2].lPer100) < ALERT_MIN_STEP_PCT) return null
    val total = rise(last[0].lPer100, last[2].lPer100)
    if (total < ALERT_MIN_TOTAL_PCT) return null
    // Only the shown figure is rounded; the gates above compared the exact rise (docs/SPEC.md §31).
    return RisingAlert(last, total.setScale(1, RoundingMode.HALF_UP))
}

/** The notification text: the three values and the rise, plus the hint. */
fun risingAlertText(alert: RisingAlert): String = Texts.alertRising(
    alert.months.joinToString(" → ") { TripsFormatting.number(it.lPer100, 1) },
    TripsFormatting.signed(alert.totalPct, 0),
)
