package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/** Records need this many full months with driving (PRD F58, docs/SPEC.md §58). */
const val ECO_RECORD_MIN_MONTHS = 3

/** A streak of months with the goal met: [current] ends at the last full month; [longest] is the best one of the archive. */
data class EcoStreakCount(val current: Int, val longest: Int)

/** The month with the record value, l/100 km or whole percent. */
data class EcoRecord(val month: MonthKey, val value: BigDecimal)

/**
 * Streaks and records of the eco goals (PRD F58, docs/SPEC.md §58). A streak is `null` without its goal. [judgedMonths] counts the
 * full months with at least [ECO_MIN_MONTH_KM] of driving; the records come from those months only.
 */
data class EcoHistory(
    val consumptionStreak: EcoStreakCount?,
    val evStreak: EcoStreakCount?,
    val lowestConsumption: EcoRecord?,
    val highestEvShare: EcoRecord?,
    val judgedMonths: Int,
)

private val HUNDRED = BigDecimal(100)

/**
 * The figures of every month come from [ecoMonth] with these goals: [GoalResult.actual] does not depend on the goal value, so
 * any goal serves to read the consumption and the electric share of a month.
 */
private val FIGURE_GOALS = EcoGoals(maxConsumption = BigDecimal.ONE, minEvSharePct = 1)

/**
 * Overload of [ecoMonth] when [trips] are already narrowed or grouped to [month],
 * avoiding repeated date calculations across full trip collections.
 */
fun ecoMonth(goals: EcoGoals, trips: List<StoredTrip>, month: MonthKey): EcoMonth {
    var km = BigDecimal.ZERO
    var fuel = BigDecimal.ZERO
    var electric = BigDecimal.ZERO
    var evKnown = false
    for (stored in trips) {
        val trip = stored.record.trip
        if (!trip.distanceKm.isFinite() || trip.distanceKm <= 0.0 || trip.fuelL.signum() < 0) continue
        km = km.add(toBigDecimal(trip.distanceKm))
        fuel = fuel.add(trip.fuelL)
        val ev = trip.evDistanceKm
        if (ev != null && ev.isFinite()) {
            evKnown = true
            if (ev > 0.0) electric = electric.add(toBigDecimal(minOf(ev, trip.distanceKm)))
        }
    }
    val enough = km.toDouble() >= ECO_MIN_MONTH_KM
    val lPer100 = if (km.signum() > 0) fuel.multiply(HUNDRED).divide(km, 2, RoundingMode.HALF_UP) else null
    val evShare = if (km.signum() > 0 && evKnown) electric.multiply(HUNDRED).divide(km, 0, RoundingMode.HALF_UP) else null
    val consumption = goals.maxConsumption?.let { goal ->
        GoalResult(goal, lPer100, if (enough && lPer100 != null) lPer100 <= goal else null)
    }
    val ev = goals.minEvSharePct?.let { pct ->
        val goal = BigDecimal(pct)
        GoalResult(goal, evShare, if (enough && evShare != null) evShare >= goal else null)
    }
    return EcoMonth(month, km.setScale(1, RoundingMode.HALF_UP), enough, consumption, ev, evKnown)
}

private fun monthOf(time: java.time.Instant, zone: ZoneId): MonthKey {
    val day = time.atZone(zone)
    return MonthKey(day.year, day.monthValue)
}

private fun nextMonth(month: MonthKey): MonthKey =
    if (month.month == 12) MonthKey(month.year + 1, 1) else MonthKey(month.year, month.month + 1)

/**
 * Streaks and records from the local archive (docs/SPEC.md §58). Only full months count: the month in progress is left out. A month
 * under [ECO_MIN_MONTH_KM], or one without a figure to judge, is skipped: it neither breaks nor extends a streak. A goal counts
 * only from its `…Since` month on (a changed goal starts a new count); without one, from the first trip of the archive.
 * Returns `null` without any goal.
 */
fun ecoHistory(goals: EcoGoals, trips: List<StoredTrip>, zone: ZoneId, today: LocalDate): EcoHistory? {
    if (!goals.any) return null
    val last = previousMonth(MonthKey(today.year, today.monthValue))
    val byMonth = trips.groupBy { monthOf(it.record.trip.start, zone) }
    val first = byMonth.keys.minOrNull()
    val months = if (first == null || first > last) {
        emptyList()
    } else {
        generateSequence(first) { month -> nextMonth(month).takeIf { it <= last } }.toList()
    }
    val judgedByGoal = months.map { ecoMonth(goals, byMonth[it].orEmpty(), it) }
    val figures = months.map { ecoMonth(FIGURE_GOALS, byMonth[it].orEmpty(), it) }.filter { it.enoughDriving }

    return EcoHistory(
        consumptionStreak = goals.maxConsumption?.let { streak(judgedByGoal, goals.consumptionSince) { it.consumption?.met } },
        evStreak = goals.minEvSharePct?.let { streak(judgedByGoal, goals.evSince) { it.ev?.met } },
        lowestConsumption = lowest(figures),
        highestEvShare = highest(figures),
        judgedMonths = figures.size,
    )
}

private fun streak(months: List<EcoMonth>, since: MonthKey?, verdict: (EcoMonth) -> Boolean?): EcoStreakCount {
    var run = 0
    var longest = 0
    for (month in months) {
        if (since != null && month.month < since) continue
        val met = verdict(month) ?: continue
        run = if (met) run + 1 else 0
        longest = maxOf(longest, run)
    }
    return EcoStreakCount(run, longest)
}

/** The lowest consumption; a tie goes to the earlier month, because the months come in order and only a lower value replaces. */
private fun lowest(months: List<EcoMonth>): EcoRecord? {
    var best: EcoRecord? = null
    for (month in months) {
        val value = month.consumption?.actual ?: continue
        if (best == null || value < best.value) best = EcoRecord(month.month, value)
    }
    return best
}

private fun highest(months: List<EcoMonth>): EcoRecord? {
    var best: EcoRecord? = null
    for (month in months) {
        val value = month.ev?.actual ?: continue
        if (best == null || value > best.value) best = EcoRecord(month.month, value)
    }
    return best
}

private fun EcoStreakCount.shown(): Boolean = current >= 2 || longest >= 2

/**
 * `Seria: spalanie 3 miesiące (najdłuższa: 5) · prąd przerwana (najdłuższa: 2)` – a part only from two months, current or longest;
 * `null` when no part reaches that.
 */
fun ecoStreakLine(history: EcoHistory?): String? {
    if (history == null) return null
    val parts = listOfNotNull(
        history.consumptionStreak?.takeIf { it.shown() }?.let { streakPart(Texts.ECO_STREAK_CONSUMPTION, it) },
        history.evStreak?.takeIf { it.shown() }?.let { streakPart(Texts.ECO_STREAK_EV, it) },
    )
    return if (parts.isEmpty()) null else Texts.ecoStreakLine(parts.joinToString(Texts.SEPARATOR))
}

private fun streakPart(label: String, count: EcoStreakCount): String =
    if (count.current == 0) {
        Texts.ecoStreakBroken(label, count.longest)
    } else {
        Texts.ecoStreakRunning(label, count.current, count.longest)
    }

/** `Rekordy: najniższe spalanie 3,92 l/100 km (wrzesień 2026) · najwyższy udział prądu 61 % (sierpień 2026)`; from three full months. */
fun ecoRecordLine(history: EcoHistory?): String? {
    if (history == null || history.judgedMonths < ECO_RECORD_MIN_MONTHS) return null
    val parts = listOfNotNull(
        history.lowestConsumption?.let {
            Texts.ecoRecordConsumption(TripsFormatting.number(it.value, 2), TripsFormatting.monthLabel(it.month))
        },
        history.highestEvShare?.let {
            Texts.ecoRecordEv(TripsFormatting.number(it.value, 0), TripsFormatting.monthLabel(it.month))
        },
    )
    return if (parts.isEmpty()) null else Texts.ecoRecordLine(parts.joinToString(Texts.SEPARATOR))
}
