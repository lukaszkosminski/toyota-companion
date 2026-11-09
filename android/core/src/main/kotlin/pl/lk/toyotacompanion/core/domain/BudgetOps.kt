package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One month's fuel and non-fuel expenses against the owner's monthly budget (PRD F48, docs/SPEC.md §49).
 * Mirrors `backend/app/domain/budget.py`.
 *
 * [fuelPln] is the sum of the priced trips of the month (0 when none is priced), [expensesPln] the stored
 * non-fuel expenses of the month. [remainingPln] may be negative, [percentUsed] is a whole percent and may
 * exceed 100; [overBudget] is `spent > limit`, so exactly the limit is not over it.
 */
data class BudgetProgress(
    val limitPln: BigDecimal,
    val fuelPln: BigDecimal,
    val expensesPln: BigDecimal,
    val spentPln: BigDecimal,
    val remainingPln: BigDecimal,
    val percentUsed: Int,
    val overBudget: Boolean,
)

private val HUNDRED = BigDecimal(100)

/**
 * The month's fuel plus expenses against [limitPln]. Money is rounded to 0.01 zł and the percent to a whole
 * percent, all HALF_UP. A missing [fuelCost] counts as 0 (the month may still have expenses); a non-positive
 * limit and negative amounts are a programming error — the settings and the forms only accept positive values.
 */
fun budgetProgress(fuelCost: BigDecimal?, expenses: BigDecimal, limitPln: BigDecimal): BudgetProgress {
    require(limitPln.signum() > 0) { "limit must be positive" }
    require(expenses.signum() >= 0) { "expenses must not be negative" }
    // Check the raw value before rounding, so -0.004 is rejected too; after rounding a tiny limit could become 0.00
    // and the percent would divide by zero.
    val limit = roundMoney(limitPln)
    require(limit.signum() > 0) { "limit must not round to zero" }
    val fuelRaw = fuelCost ?: BigDecimal.ZERO
    require(fuelRaw.signum() >= 0) { "fuel cost must not be negative" }
    val fuel = roundMoney(fuelRaw)
    val expensesPln = roundMoney(expenses)
    val spent = roundMoney(fuel.add(expensesPln))
    val remaining = roundMoney(limit.subtract(spent))
    val percent = spent.multiply(HUNDRED).divideLikePythonDecimal(limit).setScale(0, RoundingMode.HALF_UP).toInt()
    return BudgetProgress(
        limitPln = limit,
        fuelPln = fuel,
        expensesPln = expensesPln,
        spentPln = spent,
        remainingPln = remaining,
        percentUsed = percent,
        overBudget = spent > limit,
    )
}
