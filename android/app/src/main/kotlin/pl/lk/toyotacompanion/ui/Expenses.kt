package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate

/** What an expense that is not fuel was for. Fuel stays in the refuel log; these are everything else the car costs. */
enum class ExpenseCategory {
    SERVICE,
    INSPECTION,
    TYRES,
    INSURANCE,
    PARTS,
    WASH,
    FEES,
    OTHER,
}

/** One non-fuel expense; [date] may be any day, so past ones can be entered later. */
data class Expense(
    val id: String,
    val date: LocalDate,
    val category: ExpenseCategory,
    val amount: BigDecimal,
    val note: String?,
    val receiptFile: String? = null,
)

/** Sum of one category. */
data class CategoryTotal(val category: ExpenseCategory, val total: BigDecimal, val count: Int)

/** A bar in the "By category" chart (PRD F37, F38); [category] is null for the fuel share. */
data class CategoryBar(val category: ExpenseCategory?, val total: BigDecimal, val fraction: Float)

/** Sum of one month of expenses, with its fuel when [monthTotals] got it (oldest first in the list it returns). */
data class MonthTotal(val month: MonthKey, val total: BigDecimal)

/** Totals per category, biggest first; categories without an expense are left out. */
fun categoryTotals(expenses: List<Expense>): List<CategoryTotal> =
    expenses.groupBy { it.category }
        .map { (category, rows) -> CategoryTotal(category, rows.fold(BigDecimal.ZERO) { a, e -> a + e.amount }, rows.size) }
        .sortedByDescending { it.total }

/**
 * Category totals with their fraction of the largest row (docs/SPEC.md §38); empty when there is neither an expense nor fuel.
 * [fuelTotal] joins the list as a row of its own when positive.
 */
fun categoryBars(expenses: List<Expense>, fuelTotal: BigDecimal? = null): List<CategoryBar> {
    val rows = buildList {
        for (row in categoryTotals(expenses)) add(row.category to row.total)
        if (fuelTotal != null && fuelTotal.signum() > 0) add(null to fuelTotal)
    }.sortedByDescending { it.second }
    if (rows.isEmpty()) return emptyList()
    val top = rows.first().second.toFloat().coerceAtLeast(1f)
    return rows.map { (cat, total) -> CategoryBar(cat, total, total.toFloat() / top) }
}

/**
 * Totals per calendar month, oldest first, for the bars. [fuelByMonth] is the fuel money of the trips of each month
 * (docs/SPEC.md §24); it is added to the month, and a month with fuel but no other expense still gets a bar.
 */
fun monthTotals(expenses: List<Expense>, fuelByMonth: Map<MonthKey, BigDecimal> = emptyMap()): List<MonthTotal> {
    val other = expenses.groupBy { MonthKey(it.date.year, it.date.monthValue) }
        .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { a, e -> a + e.amount } }
    return (other.keys + fuelByMonth.keys).sorted()
        .map { month -> MonthTotal(month, (other[month] ?: BigDecimal.ZERO) + (fuelByMonth[month] ?: BigDecimal.ZERO)) }
}

/**
 * The amount as typed: `1 234,50`, `1234.5`, `350`; null when it is not a positive number with at most 2 decimals.
 * [allowZero] is for the estimated value of the car, where 0 is a legitimate entry (PRD F55, docs/SPEC.md §55).
 */
fun parseExpenseAmount(text: String, allowZero: Boolean = false): BigDecimal? {
    // Every whitespace goes, including the non-breaking space the app itself puts between thousands (docs/SPEC.md §38).
    val cleaned = text.filterNot { it.isWhitespace() }.replace(',', '.')
    if (!Regex("\\d{1,7}(\\.\\d{1,2})?").matches(cleaned)) return null
    val amount = BigDecimal(cleaned)
    return amount.takeIf { it.signum() > 0 || (allowZero && it.signum() == 0) }
}
