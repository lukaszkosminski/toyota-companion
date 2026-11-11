package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Total cost of ownership: fuel plus the other expenses (PRD F38, docs/SPEC.md §39, ADR-020). Mirrors `backend/app/domain/ownership.py`. */

private val HUNDRED = BigDecimal(100)

/**
 * Fuel plus expenses of one period; `null` when there is neither a fuel cost nor an expense. [fuelCost] is `null` when no
 * trip of the period has a price. Money is rounded to 0.01 zł and the fuel share to a whole percent, all HALF_UP.
 * Negative [expenses] are a programming error (the form only takes positive amounts).
 */
fun ownershipCost(
    fuelCost: BigDecimal?,
    distanceKm: BigDecimal,
    expenses: BigDecimal,
    months: Int,
    valueLossPln: BigDecimal? = null,
): OwnershipCost? {
    val expensesPln = roundMoney(expenses)
    require(expensesPln.signum() >= 0) { "expenses must not be negative" }
    val loss = valueLossPln?.let { roundMoney(it) }
    require(loss == null || loss.signum() >= 0) { "value loss must not be negative" }
    val fuelPln = fuelCost?.let { roundMoney(it) }
    if ((fuelPln == null || fuelPln.signum() <= 0) && expensesPln.signum() == 0 && (loss == null || loss.signum() <= 0)) return null
    val total = (fuelPln ?: BigDecimal.ZERO).add(expensesPln)
    val km = distanceKm
    val perKm = if (km.signum() > 0) roundMoney(total.divideLikePythonDecimal(km)) else null
    val per100 = if (km.signum() > 0) roundMoney(total.multiply(HUNDRED).divideLikePythonDecimal(km)) else null
    val perMonth = if (months > 0) roundMoney(total.divideLikePythonDecimal(BigDecimal(months))) else null
    val share = if (fuelPln != null && total.signum() > 0) {
        fuelPln.multiply(HUNDRED).divideLikePythonDecimal(total).setScale(0, RoundingMode.HALF_UP)
    } else {
        null
    }
    val totalWithLoss = loss?.let { total.add(it) }
    return OwnershipCost(
        totalPln = total,
        fuelPln = fuelPln ?: BigDecimal.ZERO,
        expensesPln = expensesPln,
        perKmPln = perKm,
        per100KmPln = per100,
        perMonthPln = perMonth,
        fuelSharePct = share,
        valueLossPln = loss,
        valueLossPerKmPln = if (loss != null && km.signum() > 0) roundMoney(loss.divideLikePythonDecimal(km)) else null,
        totalWithLossPln = totalWithLoss,
        totalWithLossPerKmPln = if (totalWithLoss != null && km.signum() > 0) roundMoney(totalWithLoss.divideLikePythonDecimal(km)) else null,
    )
}

/**
 * Months of [year] the monthly average is over (docs/SPEC.md §39): from [firstMonth] (the first month of the year with a
 * trip or an expense) to December, or to the current month in the current year; 0 for a future year.
 */
fun ownershipMonths(year: Int, firstMonth: Int, todayYear: Int, todayMonth: Int): Int {
    require(firstMonth in 1..12 && todayMonth in 1..12) { "months are 1..12" }
    if (year > todayYear) return 0
    val last = if (year == todayYear) todayMonth else 12
    return maxOf(0, last - firstMonth + 1)
}
