package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.BudgetProgress
import pl.lk.toyotacompanion.core.domain.budgetProgress
import pl.lk.toyotacompanion.core.domain.roundMoney

/**
 * The monthly budget of the owner (PRD F48, docs/SPEC.md §49): one optional limit covering fuel and the non-fuel
 * expenses. Kept in the phone's settings, not in the archive; the figures come only from the local archive and the
 * expenses table, so nothing is fetched and the car is never woken.
 */

/** One optional limit in zł; `null` = no budget (the card then offers to set one). */
data class MonthlyBudget(val limitPln: BigDecimal? = null) {
    val any: Boolean get() = limitPln != null
}

/** What the status card shows: the budget, the month in progress and its figures once read. */
data class BudgetState(
    val budget: MonthlyBudget = MonthlyBudget(),
    val progress: BudgetProgress? = null,
    /** The calendar month the progress is about; `null` before the first read. */
    val month: MonthKey? = null,
    /** A read is running; the card keeps the last figures on screen meanwhile. */
    val loading: Boolean = true,
    /** The last read failed; the limit stays, the figures may be the last good ones. */
    val failed: Boolean = false,
    /** The owner's switch for the monthly budget notification (PRD F49, docs/SPEC.md §49); on by default. */
    val alertEnabled: Boolean = true,
)

/** Result of reading the typed budget limit. */
sealed interface BudgetLimitCheck {
    data class Valid(val value: BigDecimal) : BudgetLimitCheck

    /** An empty field removes the budget. */
    data object Empty : BudgetLimitCheck

    data object Invalid : BudgetLimitCheck
}

/**
 * `800`, `800,50` or `800.50` -> a limit rounded to 0.01 zł HALF_UP; an empty field removes the budget. A zero, a
 * negative number, more than two decimals or text is invalid, never clamped (the same rules as an expense amount).
 */
fun parseBudgetLimit(text: String): BudgetLimitCheck {
    if (text.isBlank()) return BudgetLimitCheck.Empty
    val value = parseExpenseAmount(text) ?: return BudgetLimitCheck.Invalid
    return BudgetLimitCheck.Valid(roundMoney(value))
}

/** The fuel money of [month]: the stored costs of the priced trips that start in that month in [zone]. */
fun monthFuelCost(trips: List<StoredTrip>, zone: ZoneId, month: MonthKey): BigDecimal {
    var sum = BigDecimal.ZERO
    for (stored in trips) {
        val day = stored.record.trip.start.atZone(zone)
        if (day.year != month.year || day.monthValue != month.month) continue
        val cost = stored.cost ?: continue
        if (hasNoCost(cost)) continue
        cost.costPln?.let { sum = sum.add(it) }
    }
    return roundMoney(sum)
}

/** The non-fuel expenses of [month], rounded to 0.01 zł. */
fun monthExpenses(expenses: List<Expense>, month: MonthKey): BigDecimal =
    roundMoney(
        expenses.filter { it.date.year == month.year && it.date.monthValue == month.month }
            .fold(BigDecimal.ZERO) { sum, expense -> sum.add(expense.amount) },
    )

/** The month against the budget, or `null` without a limit (docs/SPEC.md §49). */
fun budgetProgressFor(
    budget: MonthlyBudget,
    trips: List<StoredTrip>,
    expenses: List<Expense>,
    zone: ZoneId,
    month: MonthKey,
): BudgetProgress? = budget.limitPln?.let {
    budgetProgress(monthFuelCost(trips, zone, month), monthExpenses(expenses, month), it)
}

/** The month of [today] as the key the archive groups by. */
fun monthOf(today: LocalDate): MonthKey = MonthKey(today.year, today.monthValue)
