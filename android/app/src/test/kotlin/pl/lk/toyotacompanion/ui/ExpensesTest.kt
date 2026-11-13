package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

private fun expense(id: String, date: String, category: ExpenseCategory, amount: String) =
    Expense(id, LocalDate.parse(date), category, BigDecimal(amount), null)

/** docs/SPEC.md §38: the amount parser and the totals behind the "Wydatki" screen. */
class ExpensesTest {
    @Test
    fun `an amount is read as typed, with a space, a comma or a dot`() {
        assertEquals(BigDecimal("1234.50"), parseExpenseAmount("1 234,50"))
        assertEquals(BigDecimal("1234.5"), parseExpenseAmount("1234.5"))
        assertEquals(BigDecimal("350"), parseExpenseAmount("350"))
        assertEquals(BigDecimal("350"), parseExpenseAmount("  350  "))
        assertEquals(BigDecimal("9999999.99"), parseExpenseAmount("9999999,99"))
        // The app groups thousands with a non-breaking space, so a copied amount must be read too.
        assertEquals(BigDecimal("1234.50"), parseExpenseAmount("1\u00A0234,50"))
        assertEquals(BigDecimal("1234.50"), parseExpenseAmount("\t1 234,50\n"))
    }

    @Test
    fun `zero, negative, too long and malformed amounts are refused`() {
        assertNull(parseExpenseAmount("0"))
        assertNull(parseExpenseAmount("0,00"))
        assertNull(parseExpenseAmount("-5"))
        assertNull(parseExpenseAmount("12345678"))
        assertNull(parseExpenseAmount("1,234"))
        assertNull(parseExpenseAmount(""))
        assertNull(parseExpenseAmount("   "))
        assertNull(parseExpenseAmount("abc"))
        assertNull(parseExpenseAmount("12,5zł"))
        assertNull(parseExpenseAmount("1,2,3"))
    }

    @Test
    fun `categories are summed, biggest first, and empty ones are left out`() {
        val totals = categoryTotals(
            listOf(
                expense("1", "2026-01-10", ExpenseCategory.WASH, "30.00"),
                expense("2", "2026-02-10", ExpenseCategory.SERVICE, "400.50"),
                expense("3", "2026-03-10", ExpenseCategory.WASH, "25.00"),
                expense("4", "2026-03-11", ExpenseCategory.INSURANCE, "900"),
                expense("5", "2026-03-12", ExpenseCategory.SERVICE, "100.25"),
            ),
        )
        assertEquals(
            listOf(ExpenseCategory.INSURANCE, ExpenseCategory.SERVICE, ExpenseCategory.WASH),
            totals.map { it.category },
        )
        assertEquals(listOf("900", "500.75", "55.00"), totals.map { it.total.toPlainString() })
        assertEquals(listOf(1, 2, 2), totals.map { it.count })
        assertTrue(totals.none { it.category == ExpenseCategory.TYRES })
    }

    @Test
    fun `no expenses give no totals`() {
        assertEquals(emptyList(), categoryTotals(emptyList()))
        assertEquals(emptyList(), monthTotals(emptyList()))
        assertEquals(emptyList(), categoryBars(emptyList(), null))
        assertEquals(emptyList(), categoryBars(emptyList(), BigDecimal.ZERO))
        assertEquals(emptyList(), categoryBars(emptyList(), BigDecimal("-10")))
    }

    @Test
    fun `category bars normalize totals against top row and fuel joins as null category`() {
        val bars = categoryBars(
            listOf(
                expense("1", "2026-01-10", ExpenseCategory.WASH, "50.00"),
                expense("2", "2026-02-10", ExpenseCategory.SERVICE, "200.00"),
            ),
            fuelTotal = BigDecimal("400.00"),
        )
        assertEquals(listOf(null, ExpenseCategory.SERVICE, ExpenseCategory.WASH), bars.map { it.category })
        assertEquals(listOf(BigDecimal("400.00"), BigDecimal("200.00"), BigDecimal("50.00")), bars.map { it.total })
        assertEquals(1.0f, bars[0].fraction)
        assertEquals(0.5f, bars[1].fraction)
        assertEquals(0.125f, bars[2].fraction)
    }

    @Test
    fun `fuel alone produces a single category bar`() {
        val bars = categoryBars(emptyList(), BigDecimal("120.00"))
        assertEquals(1, bars.size)
        assertNull(bars[0].category)
        assertEquals(BigDecimal("120.00"), bars[0].total)
        assertEquals(1.0f, bars[0].fraction)
    }

    @Test
    fun `months are summed, oldest first, across a year boundary`() {
        val totals = monthTotals(
            listOf(
                expense("1", "2027-01-05", ExpenseCategory.FEES, "10.00"),
                expense("2", "2026-12-31", ExpenseCategory.PARTS, "200.00"),
                expense("3", "2026-12-01", ExpenseCategory.PARTS, "50.50"),
                expense("4", "2026-03-15", ExpenseCategory.OTHER, "5.00"),
            ),
        )
        assertEquals(listOf(MonthKey(2026, 3), MonthKey(2026, 12), MonthKey(2027, 1)), totals.map { it.month })
        assertEquals(listOf("5.00", "250.50", "10.00"), totals.map { it.total.toPlainString() })
    }

    @Test
    fun `fuel is added to its month, and a month with only fuel still gets a bar`() {
        val totals = monthTotals(
            listOf(
                expense("1", "2026-03-15", ExpenseCategory.OTHER, "5.00"),
                expense("2", "2026-05-02", ExpenseCategory.SERVICE, "100.00"),
            ),
            mapOf(
                MonthKey(2026, 5) to BigDecimal("250.40"),
                MonthKey(2026, 4) to BigDecimal("80.10"),
            ),
        )
        assertEquals(listOf(MonthKey(2026, 3), MonthKey(2026, 4), MonthKey(2026, 5)), totals.map { it.month })
        assertEquals(listOf("5.00", "80.10", "350.40"), totals.map { it.total.toPlainString() })
    }

    @Test
    fun `fuel alone gives the months of the fuel and no fuel changes nothing`() {
        assertEquals(
            listOf(MonthKey(2026, 1)),
            monthTotals(emptyList(), mapOf(MonthKey(2026, 1) to BigDecimal("10.00"))).map { it.month },
        )
        val one = listOf(expense("1", "2026-03-15", ExpenseCategory.OTHER, "5.00"))
        assertEquals(monthTotals(one), monthTotals(one, emptyMap()))
    }
}
