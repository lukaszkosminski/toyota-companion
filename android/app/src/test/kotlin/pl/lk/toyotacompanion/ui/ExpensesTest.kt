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
}
