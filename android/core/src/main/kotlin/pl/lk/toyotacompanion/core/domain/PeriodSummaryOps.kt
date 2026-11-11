package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The period summary notification (PRD F54, docs/SPEC.md §54): one notification per completed week (Monday to Sunday) or calendar month, sent by
 * the first synchronization after the period ended. Mirrors `backend/app/domain/period_summary.py` (the same test names live in
 * `backend/tests/test_domain_period_summary.py`). Pure: dates and the hour come in, nothing is read from the phone here.
 */

/** Which period the summary covers; `null` at the call site means the summary is off. */
enum class SummaryPeriod { WEEK, MONTH }

/** Quiet hours: the notification is sent only from [SUMMARY_FROM_HOUR] up to, not including, [SUMMARY_UNTIL_HOUR] (local time). */
const val SUMMARY_FROM_HOUR: Int = 8
const val SUMMARY_UNTIL_HOUR: Int = 22

/**
 * A period of local dates: [from] inclusive, [until] exclusive. The key [SummaryWindow.key] names it for the settings: `week:2026-11-02` (the
 * Monday) or `month:2026-10`. A week is named by its Monday, not by an ISO week number, so the year boundary needs no special case.
 */
data class SummaryWindow(val period: SummaryPeriod, val from: LocalDate, val until: LocalDate) {
    val key: String
        get() = when (period) {
            SummaryPeriod.WEEK -> "week:$from"
            SummaryPeriod.MONTH -> "month:" + from.year.toString() + "-" + from.monthValue.toString().padStart(2, '0')
        }
}

/** The last period that is completely over on [today]: the week before this Monday, or the month before this one. */
fun lastCompletedWindow(period: SummaryPeriod, today: LocalDate): SummaryWindow = when (period) {
    SummaryPeriod.WEEK -> {
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        SummaryWindow(period, thisMonday.minusWeeks(1), thisMonday)
    }
    SummaryPeriod.MONTH -> {
        val thisMonth = today.withDayOfMonth(1)
        SummaryWindow(period, thisMonth.minusMonths(1), thisMonth)
    }
}

/** The period of the same length right before [window], for the comparison. */
fun previousWindow(window: SummaryWindow): SummaryWindow = when (window.period) {
    SummaryPeriod.WEEK -> SummaryWindow(window.period, window.from.minusWeeks(1), window.from)
    SummaryPeriod.MONTH -> SummaryWindow(window.period, window.from.minusMonths(1), window.from)
}

/**
 * The sums of one period. [fuelCostPln] is `null` when no trip of the period has a price (never 0 zł), [lPer100Km] is
 * `Σ litres * 100 / Σ km` (0,01) and `null` without distance, [evSharePct] is `Σ electric km / Σ km` in percent (0,1) and `null` without
 * electric data. [trips] counts the usable trips (a distance above zero, fuel not negative).
 */
data class PeriodTotals(
    val trips: Int,
    val distanceKm: BigDecimal,
    val fuelL: BigDecimal,
    val fuelCostPln: BigDecimal?,
    val expensesPln: BigDecimal,
    val lPer100Km: BigDecimal?,
    val evSharePct: BigDecimal?,
) {
    /** Fuel plus the other expenses; the fuel counts as 0 without a price, which is said where the total is shown. */
    val costPln: BigDecimal get() = (fuelCostPln ?: BigDecimal.ZERO).add(expensesPln)

    /** A period with neither a trip nor an expense has nothing to tell. */
    val isEmpty: Boolean get() = trips == 0 && expensesPln.signum() == 0
}

/**
 * The totals of [window] in [zone]: a trip belongs to the local date of its start, an expense to its date. A priced trip is one with an amount
 * and not source `NONE` (as the trip list). Expenses are `(date, amount)` pairs, so the domain needs no UI type.
 */
fun periodTotals(
    trips: List<Trip>,
    costs: List<TripCost>,
    expenses: List<Pair<LocalDate, BigDecimal>>,
    window: SummaryWindow,
    zone: ZoneId,
): PeriodTotals {
    fun inWindow(date: LocalDate) = !date.isBefore(window.from) && date.isBefore(window.until)
    val costByTrip = costs.associateBy { it.tripId }
    val usable = trips.filter { inWindow(it.start.atZone(zone).toLocalDate()) && it.distanceKm.isFinite() && it.distanceKm > 0.0 && it.fuelL.signum() >= 0 }
    var km = BigDecimal.ZERO
    var fuel = BigDecimal.ZERO
    var fuelCost: BigDecimal? = null
    for (trip in usable) {
        km = km.add(toBigDecimal(trip.distanceKm))
        fuel = fuel.add(trip.fuelL)
        val cost = costByTrip[trip.id]
        val amount = cost?.costPln
        if (cost != null && amount != null && cost.source != CostSource.NONE) fuelCost = fuelCost?.add(amount) ?: amount
    }
    val expensesPln = expenses.filter { inWindow(it.first) }.fold(BigDecimal.ZERO) { sum, e -> sum.add(e.second) }
    return PeriodTotals(
        trips = usable.size,
        distanceKm = km.setScale(1, RoundingMode.HALF_UP),
        fuelL = fuel.setScale(1, RoundingMode.HALF_UP),
        fuelCostPln = fuelCost?.setScale(2, RoundingMode.HALF_UP),
        expensesPln = expensesPln.setScale(2, RoundingMode.HALF_UP),
        lPer100Km = if (km.signum() > 0) fuel.multiply(BigDecimal(100)).divideLikePythonDecimal(km).setScale(2, RoundingMode.HALF_UP) else null,
        evSharePct = aggregateElectricShare(usable)?.setScale(1, RoundingMode.HALF_UP),
    )
}

/** The change of [now] against [before] in whole percent, HALF_UP; `null` without a positive base (no 0 % for a missing value). */
fun percentChangeOf(now: BigDecimal?, before: BigDecimal?): BigDecimal? {
    if (now == null || before == null || before.signum() <= 0) return null
    return now.subtract(before).multiply(BigDecimal(100)).divideLikePythonDecimal(before).setScale(0, RoundingMode.HALF_UP)
}

/** What the notifier does after a synchronization (docs/SPEC.md §54). */
sealed interface SummaryDecision {
    /** No summary is wanted: the switch is off. */
    data object Nothing : SummaryDecision

    /** The period is not sent now (quiet hours); the key is not marked, so the next synchronization in daytime sends it. */
    data object Wait : SummaryDecision

    /** First run or a changed period: [key] is remembered without a notification, so an old period is never sent as news. */
    data class Baseline(val key: String) : SummaryDecision

    /** The last completed period has not been sent yet and it is daytime. */
    data class Post(val window: SummaryWindow) : SummaryDecision
}

/**
 * The decision for one synchronization (PRD F54). Only the last completed period is ever sent, never the ones missed before it. The quiet
 * hours are the local hour [hour] of the phone, `8` up to `22` exclusive.
 */
fun summaryDecision(period: SummaryPeriod?, lastKey: String?, today: LocalDate, hour: Int): SummaryDecision {
    if (period == null) return SummaryDecision.Nothing
    val window = lastCompletedWindow(period, today)
    return when {
        lastKey == null -> SummaryDecision.Baseline(window.key)
        lastKey == window.key -> SummaryDecision.Nothing
        hour < SUMMARY_FROM_HOUR || hour >= SUMMARY_UNTIL_HOUR -> SummaryDecision.Wait
        else -> SummaryDecision.Post(window)
    }
}
