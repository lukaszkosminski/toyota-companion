@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertBd(expected: String, actual: BigDecimal?, label: String = "value") {
    val e = BigDecimal(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

private fun d(value: String) = BigDecimal(value)

/** Mirrors backend/tests/test_domain_ownership.py - the same names without the `test_` prefix. */
class OwnershipOpsTest {

    @Test
    fun `fuel_and_expenses_add_up_per_km_and_month`() {
        val cost = requireNotNull(ownershipCost(d("1239.60"), d("4807.0"), d("1500.00"), 8))
        assertBd("2739.60", cost.totalPln, "total")
        assertBd("1239.60", cost.fuelPln, "fuel")
        assertBd("1500.00", cost.expensesPln, "expenses")
        assertBd("0.57", cost.perKmPln, "perKm")
        assertBd("56.99", cost.per100KmPln, "per100")
        assertBd("342.45", cost.perMonthPln, "perMonth")
        assertBd("45", cost.fuelSharePct, "share")
    }

    @Test
    fun `without_expenses_it_is_the_fuel_cost`() {
        val cost = requireNotNull(ownershipCost(d("100.00"), d("400.0"), d("0"), 2))
        assertBd("100.00", cost.totalPln)
        assertBd("25.00", cost.per100KmPln)
        assertBd("100", cost.fuelSharePct)
    }

    @Test
    fun `without_a_fuel_price_only_expenses_count_and_no_share`() {
        val cost = requireNotNull(ownershipCost(null, d("400.0"), d("250.50"), 3))
        assertBd("250.50", cost.totalPln)
        assertBd("0", cost.fuelPln)
        assertNull(cost.fuelSharePct)
        assertBd("83.50", cost.perMonthPln)
    }

    @Test
    fun `nothing_to_count_is_none`() {
        assertNull(ownershipCost(null, d("400.0"), d("0"), 3))
        assertNull(ownershipCost(d("0"), d("400.0"), d("0"), 3))
    }

    @Test
    fun `no_distance_and_no_months_leave_the_rates_out`() {
        val cost = requireNotNull(ownershipCost(null, d("0"), d("99.99"), 0))
        assertNull(cost.perKmPln)
        assertNull(cost.per100KmPln)
        assertNull(cost.perMonthPln)
    }

    @Test
    fun `rounding_is_half_up`() {
        assertBd("0.01", requireNotNull(ownershipCost(d("0.005"), d("1"), d("0"), 1)).totalPln)
        val third = requireNotNull(ownershipCost(d("10.00"), d("3"), d("0"), 3))
        assertBd("3.33", third.perKmPln)
        assertBd("3.33", third.perMonthPln)
        assertBd("50", requireNotNull(ownershipCost(d("1.00"), d("1"), d("1.00"), 1)).fuelSharePct)
        assertBd("33", requireNotNull(ownershipCost(d("1.00"), d("1"), d("2.00"), 1)).fuelSharePct)
    }

    @Test
    fun `negative_expenses_are_rejected`() {
        assertFailsWith<IllegalArgumentException> { ownershipCost(d("1.00"), d("1"), d("-1.00"), 1) }
    }

    @Test
    fun `months_from_first_activity_to_december`() {
        assertEquals(9, ownershipMonths(2025, 4, 2026, 11))
        assertEquals(12, ownershipMonths(2025, 1, 2026, 11))
    }

    @Test
    fun `months_in_the_current_year_end_this_month`() {
        assertEquals(8, ownershipMonths(2026, 4, 2026, 11))
        assertEquals(1, ownershipMonths(2026, 11, 2026, 11))
    }

    @Test
    fun `months_of_a_future_year_or_a_later_first_month_are_zero`() {
        assertEquals(0, ownershipMonths(2027, 1, 2026, 11))
        assertEquals(0, ownershipMonths(2026, 12, 2026, 11))
    }

    @Test
    fun `months_outside_one_to_twelve_are_rejected`() {
        assertFailsWith<IllegalArgumentException> { ownershipMonths(2026, 0, 2026, 11) }
        assertFailsWith<IllegalArgumentException> { ownershipMonths(2026, 1, 2026, 13) }
    }

    @Test
    fun `year_before_purchase_is_zero`() {
        assertBd("0.00", valueLossInYear(d("10000"), LocalDate.of(2024, 1, 1), d("8000"), LocalDate.of(2025, 1, 1), 2023))
    }

    @Test
    fun `year_of_purchase_counts_days_from_purchase`() {
        // 1000 zl over 366 days from 1 June 2023 to 1 June 2024; 2023 has 214 of those days.
        assertBd("584.70", valueLossInYear(d("1000"), LocalDate.of(2023, 6, 1), d("0"), LocalDate.of(2024, 6, 1), 2023))
    }

    @Test
    fun `year_with_the_whole_period_is_the_loss`() {
        assertBd("2000.00", valueLossInYear(d("10000"), LocalDate.of(2024, 1, 1), d("8000"), LocalDate.of(2025, 1, 1), 2024))
    }

    @Test
    fun `year_after_the_valuation_is_zero`() {
        assertBd("0.00", valueLossInYear(d("10000"), LocalDate.of(2024, 1, 1), d("8000"), LocalDate.of(2025, 1, 1), 2025))
    }

    @Test
    fun `leap_february_enters_the_daily_rate`() {
        // 290 zl over the 29 days of February 2024: the leap day is part of the rate.
        assertBd("290.00", valueLossInYear(d("290"), LocalDate.of(2024, 2, 1), d("0"), LocalDate.of(2024, 3, 1), 2024))
    }

    @Test
    fun `value_at_or_above_the_purchase_is_zero`() {
        assertBd("0.00", valueLossInYear(d("100000"), LocalDate.of(2024, 1, 1), d("120000"), LocalDate.of(2025, 1, 1), 2024))
    }

    @Test
    fun `valuation_date_equal_to_purchase_is_zero`() {
        assertBd("0.00", valueLossInYear(d("100000"), LocalDate.of(2024, 1, 1), d("50000"), LocalDate.of(2024, 1, 1), 2024))
    }

    @Test
    fun `invalid_valuation_dates_and_prices_are_rejected`() {
        assertFailsWith<IllegalArgumentException> {
            valueLossInYear(d("0"), LocalDate.of(2024, 1, 1), d("0"), LocalDate.of(2025, 1, 1), 2024)
        }
        assertFailsWith<IllegalArgumentException> {
            valueLossInYear(d("1000"), LocalDate.of(2024, 1, 1), d("-1"), LocalDate.of(2025, 1, 1), 2024)
        }
        assertFailsWith<IllegalArgumentException> {
            valueLossInYear(d("1000"), LocalDate.of(2024, 1, 1), d("500"), LocalDate.of(2023, 12, 31), 2024)
        }
    }

    @Test
    fun `ownership_with_loss_keeps_cash_and_adds_its_own_rows`() {
        val cost = requireNotNull(ownershipCost(d("1239.60"), d("4807.0"), d("1500.00"), 8, valueLossPln = d("1000")))
        assertBd("2739.60", cost.totalPln, "total")
        assertBd("1000.00", cost.valueLossPln, "loss")
        assertBd("0.21", cost.valueLossPerKmPln, "lossPerKm")
        assertBd("3739.60", cost.totalWithLossPln, "totalWithLoss")
        assertBd("0.78", cost.totalWithLossPerKmPln, "totalWithLossPerKm")
    }

    @Test
    fun `ownership_with_only_the_loss_is_not_none`() {
        val cost = requireNotNull(ownershipCost(null, d("0"), d("0"), 0, valueLossPln = d("123.45")))
        assertBd("0", cost.totalPln)
        assertBd("123.45", cost.totalWithLossPln)
        assertNull(cost.perKmPln)
    }

    @Test
    fun `negative_loss_is_rejected`() {
        assertFailsWith<IllegalArgumentException> { ownershipCost(null, d("0"), d("0"), 0, valueLossPln = d("-1")) }
    }
}
