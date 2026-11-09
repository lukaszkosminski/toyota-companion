package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The monthly budget (PRD F48, docs/SPEC.md §49); the same test names live in `backend/tests/test_domain_budget.py`. */
class BudgetOpsTest {

    @Test
    fun `fuel_and_expenses_add_up_against_the_limit`() {
        val p = budgetProgress(BigDecimal("342.45"), BigDecimal("250.50"), BigDecimal("800"))

        assertEquals(BigDecimal("800.00"), p.limitPln)
        assertEquals(BigDecimal("342.45"), p.fuelPln)
        assertEquals(BigDecimal("250.50"), p.expensesPln)
        assertEquals(BigDecimal("592.95"), p.spentPln)
        assertEquals(BigDecimal("207.05"), p.remainingPln)
        assertEquals(74, p.percentUsed)
        assertFalse(p.overBudget)
    }

    @Test
    fun `nothing_spent_is_zero_percent_and_the_whole_limit_left`() {
        val p = budgetProgress(null, BigDecimal("0"), BigDecimal("500"))

        assertEquals(BigDecimal("0.00"), p.fuelPln)
        assertEquals(BigDecimal("0.00"), p.spentPln)
        assertEquals(BigDecimal("500.00"), p.remainingPln)
        assertEquals(0, p.percentUsed)
        assertFalse(p.overBudget)
    }

    @Test
    fun `a_missing_fuel_cost_counts_as_zero`() {
        val p = budgetProgress(null, BigDecimal("120.00"), BigDecimal("500"))

        assertEquals(BigDecimal("120.00"), p.spentPln)
        assertEquals(24, p.percentUsed)
        assertFalse(p.overBudget)
    }

    @Test
    fun `over_budget_keeps_a_negative_remaining_and_a_percent_above_hundred`() {
        val p = budgetProgress(BigDecimal("90.00"), BigDecimal("60.00"), BigDecimal("100"))

        assertEquals(BigDecimal("150.00"), p.spentPln)
        assertEquals(BigDecimal("-50.00"), p.remainingPln)
        assertEquals(150, p.percentUsed)
        assertTrue(p.overBudget)
    }

    @Test
    fun `exactly_the_limit_is_not_over_budget`() {
        val p = budgetProgress(BigDecimal("60.00"), BigDecimal("40.00"), BigDecimal("100"))

        assertEquals(BigDecimal("0.00"), p.remainingPln)
        assertEquals(100, p.percentUsed)
        assertFalse(p.overBudget)
    }

    @Test
    fun `rounding_is_half_up`() {
        val p = budgetProgress(BigDecimal("0.005"), BigDecimal("0.005"), BigDecimal("3"))

        assertEquals(BigDecimal("0.01"), p.fuelPln)
        assertEquals(BigDecimal("0.01"), p.expensesPln)
        assertEquals(BigDecimal("0.02"), p.spentPln)
        assertEquals(1, p.percentUsed)
    }

    @Test
    fun `a_non_positive_limit_is_rejected`() {
        assertFailsWith<IllegalArgumentException> { budgetProgress(null, BigDecimal("1.00"), BigDecimal("0")) }
        assertFailsWith<IllegalArgumentException> { budgetProgress(null, BigDecimal("1.00"), BigDecimal("-5")) }
    }

    @Test
    fun `a_limit_that_rounds_to_zero_is_rejected`() {
        // 0.004 > 0, but rounds to 0.00; dividing by it would be a zero divisor.
        assertFailsWith<IllegalArgumentException> { budgetProgress(null, BigDecimal("1.00"), BigDecimal("0.004")) }
    }

    @Test
    fun `percent_rounds_half_up`() {
        assertEquals(1, budgetProgress(BigDecimal("1.00"), BigDecimal("0"), BigDecimal("200")).percentUsed)
        assertEquals(100, budgetProgress(BigDecimal("199.00"), BigDecimal("0"), BigDecimal("200")).percentUsed)
        assertEquals(101, budgetProgress(BigDecimal("201.00"), BigDecimal("0"), BigDecimal("200")).percentUsed)
    }

    @Test
    fun `a_limit_of_one_grosz_is_allowed`() {
        val p = budgetProgress(null, BigDecimal("0.01"), BigDecimal("0.01"))

        assertEquals(BigDecimal("0.01"), p.limitPln)
        assertEquals(BigDecimal("0.00"), p.remainingPln)
        assertEquals(100, p.percentUsed)
        assertFalse(p.overBudget)
    }

    @Test
    fun `over_by_one_grosz_is_over_budget`() {
        val p = budgetProgress(null, BigDecimal("100.01"), BigDecimal("100"))

        assertEquals(BigDecimal("-0.01"), p.remainingPln)
        assertEquals(100, p.percentUsed)
        assertTrue(p.overBudget)
    }

    @Test
    fun `negative_expenses_or_fuel_are_rejected`() {
        assertFailsWith<IllegalArgumentException> { budgetProgress(null, BigDecimal("-1.00"), BigDecimal("100")) }
        assertFailsWith<IllegalArgumentException> { budgetProgress(BigDecimal("-1.00"), BigDecimal("1.00"), BigDecimal("100")) }
        assertFailsWith<IllegalArgumentException> { budgetProgress(null, BigDecimal("-0.004"), BigDecimal("100")) }
        assertFailsWith<IllegalArgumentException> { budgetProgress(BigDecimal("-0.004"), BigDecimal("1.00"), BigDecimal("100")) }
    }
}
