package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

/** The monthly budget (PRD F48, docs/SPEC.md §49): parsing, the month's sums and the figures of the card. */
private val UTC: ZoneId = ZoneOffset.UTC

private val MONTH = MonthKey(2026, 10)

private val IN_MONTH: Instant = Instant.parse("2026-10-15T10:00:00Z")

private fun stored(
    id: String,
    start: Instant = IN_MONTH,
    cost: TripCost? = TripCost(id, BigDecimal("100.00"), BigDecimal("6.19"), "r1", CostSource.REFUEL),
): StoredTrip = StoredTrip(
    record = TripRecord(
        trip = Trip(id, start, start.plusSeconds(1_800), 12.3, BigDecimal("0.600"), null),
        vin = "VIN1",
    ),
    cost = cost,
)

private fun expense(id: String, date: LocalDate = LocalDate.of(2026, 10, 5), amount: String = "50.00"): Expense =
    Expense(id, date, ExpenseCategory.SERVICE, BigDecimal(amount), null)

class BudgetTest {

    @Test
    fun `an empty field removes the budget`() {
        assertEquals(BudgetLimitCheck.Empty, parseBudgetLimit(""))
        assertEquals(BudgetLimitCheck.Empty, parseBudgetLimit("   "))
    }

    @Test
    fun `a limit is rounded to two places and accepts a comma or a dot`() {
        assertEquals(BudgetLimitCheck.Valid(BigDecimal("800.00")), parseBudgetLimit("800"))
        assertEquals(BudgetLimitCheck.Valid(BigDecimal("800.50")), parseBudgetLimit("800,50"))
        assertEquals(BudgetLimitCheck.Valid(BigDecimal("800.50")), parseBudgetLimit(" 800.5 "))
        assertEquals(BudgetLimitCheck.Valid(BigDecimal("1000.00")), parseBudgetLimit("1 000"))
    }

    @Test
    fun `zero, negative, too many decimals and text are invalid, never clamped`() {
        for (text in listOf("0", "-5", "800,505", "abc", "800,5,0", "1e3", "1.234,56", "12345678")) {
            assertEquals(BudgetLimitCheck.Invalid, parseBudgetLimit(text), text)
        }
    }

    @Test
    fun `spaces and a non-breaking space are ignored, a plain thousands dot is not`() {
        assertEquals(BudgetLimitCheck.Valid(BigDecimal("1234.50")), parseBudgetLimit("1 234,50"))
        assertEquals(BudgetLimitCheck.Valid(BigDecimal("1234.50")), parseBudgetLimit("1\u00A0234,50"))
        assertEquals(BudgetLimitCheck.Invalid, parseBudgetLimit("1.234,56"))
    }

    @Test
    fun `month fuel counts only priced trips of that month`() {
        val trips = listOf(
            stored("a", cost = TripCost("a", BigDecimal("100.00"), BigDecimal("6.19"), "r1", CostSource.REFUEL)),
            stored("b", cost = TripCost("b", BigDecimal("50.25"), BigDecimal("6.19"), "r1", CostSource.DEFAULT)),
            stored("c", cost = null),
            stored("d", cost = TripCost("d", null, BigDecimal("6.19"), "r1", CostSource.REFUEL)),
            stored("e", cost = TripCost("e", BigDecimal("100.00"), BigDecimal("6.19"), null, CostSource.NONE)),
            stored("f", start = Instant.parse("2026-09-30T10:00:00Z")),
            stored("g", start = Instant.parse("2026-11-01T00:30:00Z")),
        )

        assertEquals(BigDecimal("150.25"), monthFuelCost(trips, UTC, MONTH))
    }

    @Test
    fun `month fuel follows the local month of the trip start`() {
        // 23:30 UTC on 31 October is already 00:30 on 1 November in Warsaw (CET, +1).
        val trips = listOf(stored("late", start = Instant.parse("2026-10-31T23:30:00Z")))

        assertEquals(BigDecimal("0.00"), monthFuelCost(trips, ZoneId.of("Europe/Warsaw"), MONTH))
        assertEquals(BigDecimal("100.00"), monthFuelCost(trips, UTC, MONTH))
    }

    @Test
    fun `the month survives the spring dst change in the local zone`() {
        // DST starts on 29 March 2026 at 02:00 in Warsaw; 23:30 UTC on the 28th is 00:30 on the 29th (still +1).
        val trips = listOf(stored("spring", start = Instant.parse("2026-03-28T23:30:00Z")))

        assertEquals(BigDecimal("100.00"), monthFuelCost(trips, ZoneId.of("Europe/Warsaw"), MonthKey(2026, 3)))
        assertEquals(BigDecimal("0.00"), monthFuelCost(trips, ZoneId.of("Europe/Warsaw"), MonthKey(2026, 2)))
    }

    @Test
    fun `month expenses count only that month`() {
        val expenses = listOf(
            expense("a", LocalDate.of(2026, 10, 1), "50.00"),
            expense("b", LocalDate.of(2026, 10, 31), "25.50"),
            expense("c", LocalDate.of(2026, 9, 30), "999.00"),
            expense("d", LocalDate.of(2026, 11, 1), "999.00"),
        )

        assertEquals(BigDecimal("75.50"), monthExpenses(expenses, MONTH))
    }

    @Test
    fun `without a limit there is no progress`() {
        assertNull(budgetProgressFor(MonthlyBudget(), listOf(stored("a")), listOf(expense("e")), UTC, MONTH))
    }

    @Test
    fun `with a limit the figures add fuel and expenses`() {
        val progress = budgetProgressFor(
            MonthlyBudget(BigDecimal("800.00")),
            listOf(stored("a")),
            listOf(expense("e", amount = "50.00")),
            UTC,
            MONTH,
        )

        assertEquals(BigDecimal("150.00"), progress?.spentPln)
        assertEquals(BigDecimal("650.00"), progress?.remainingPln)
        assertEquals(19, progress?.percentUsed)
        assertFalse(requireNotNull(progress).overBudget)
    }

    @Test
    fun `the month of a day is its calendar month`() {
        assertEquals(MonthKey(2026, 11), monthOf(LocalDate.of(2026, 11, 9)))
        assertEquals(MonthKey(2026, 1), monthOf(LocalDate.of(2026, 1, 1)))
    }
}
