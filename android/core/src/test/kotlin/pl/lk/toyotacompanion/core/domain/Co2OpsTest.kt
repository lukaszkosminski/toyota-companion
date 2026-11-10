package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The CO2 footprint of burned gasoline (PRD F51, docs/SPEC.md §51); the same test names live in `backend/tests/test_domain_co2.py`. */
class Co2OpsTest {

    @Test
    fun `co2_of_one_litre_is_the_constant`() {
        assertEquals(BigDecimal("2.3"), co2Kg(BigDecimal("1")))
    }

    @Test
    fun `co2_of_ten_litres_is_ten_times_the_constant`() {
        assertEquals(BigDecimal("23.0"), co2Kg(BigDecimal("10")))
    }

    @Test
    fun `co2_rounds_to_one_tenth_of_a_kilogram_half_up`() {
        // 0.5 l * 2.3 = 1.15 kg, HALF_UP to one decimal -> 1.2 kg.
        assertEquals(BigDecimal("1.2"), co2Kg(BigDecimal("0.5")))
        assertEquals(BigDecimal("0.9"), co2Kg(BigDecimal("0.4")))
    }

    @Test
    fun `co2_tie_rounds_half_up_not_to_even`() {
        // 1.5 l * 2.3 = 3.45 kg: HALF_UP gives 3.5, banker's rounding would give 3.4.
        assertEquals(BigDecimal("3.5"), co2Kg(BigDecimal("1.5")))
        assertEquals(BigDecimal("5.8"), co2Kg(BigDecimal("2.5")))
    }

    @Test
    fun `co2_of_a_realistic_month`() {
        // 52.371 l * 2.3 = 120.4533 kg -> 120.5 kg; litres come in 0.001 l steps.
        assertEquals(BigDecimal("120.5"), co2Kg(BigDecimal("52.371")))
        assertEquals(BigDecimal("2839.5"), co2Kg(BigDecimal("1234.567")))
    }

    @Test
    fun `co2_of_zero_litres_is_zero`() {
        assertEquals(BigDecimal("0.0"), co2Kg(BigDecimal("0")))
    }

    @Test
    fun `negative_litres_are_rejected`() {
        assertFailsWith<IllegalArgumentException> { co2Kg(BigDecimal("-0.1")) }
        assertFailsWith<IllegalArgumentException> { co2Kg(BigDecimal("-0.004")) }
    }
}
