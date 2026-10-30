package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The single field of the default price form (docs/SPEC.md §12.3): the keyboard
 * syntax of docs/SPEC.md §11.2, the sign of §2 and the price rounding of §2.
 * The expected messages are the [Texts] constants, never copies of the Polish text.
 */
class DefaultPriceFormTest {
    @Test
    fun `comma and dot are both accepted`() {
        for (text in listOf("6,19", "6.19", "  6,19  ")) {
            val check = assertIs<DefaultPriceCheck.Valid>(parseDefaultPrice(text), text)
            assertEquals(0, BigDecimal("6.190").compareTo(check.price), text)
        }
    }

    @Test
    fun `the price is rounded to 0001 zl half up`() {
        val up = assertIs<DefaultPriceCheck.Valid>(parseDefaultPrice("6,1945"))
        assertEquals(0, BigDecimal("6.195").compareTo(up.price), "HALF_UP w gore")

        val down = assertIs<DefaultPriceCheck.Valid>(parseDefaultPrice("6,1944"))
        assertEquals(0, BigDecimal("6.194").compareTo(down.price))

        // Rounding may carry into the zl, like a pump display does.
        val carry = assertIs<DefaultPriceCheck.Valid>(parseDefaultPrice("6,1999"))
        assertEquals(0, BigDecimal("6.200").compareTo(carry.price))
    }

    @Test
    fun `an empty field is a mistake, not a removal`() {
        val empty = assertIs<DefaultPriceCheck.Invalid>(parseDefaultPrice(""))
        assertEquals(Texts.DEFAULT_PRICE_EMPTY_ERROR, empty.message)

        val blank = assertIs<DefaultPriceCheck.Invalid>(parseDefaultPrice("   "))
        assertEquals(Texts.DEFAULT_PRICE_EMPTY_ERROR, blank.message)
    }

    @Test
    fun `text that is not a number is refused`() {
        for (text in listOf("abc", "6,1,9", "6..19", ",", "-", "six")) {
            val invalid = assertIs<DefaultPriceCheck.Invalid>(parseDefaultPrice(text), text)
            assertEquals(Texts.REFUEL_NUMBER_ERROR, invalid.message, text)
        }
    }

    @Test
    fun `zero and negative prices are refused`() {
        for (text in listOf("0", "0,00", "-1", "-0,5")) {
            val invalid = assertIs<DefaultPriceCheck.Invalid>(parseDefaultPrice(text), text)
            assertEquals(Texts.REFUEL_POSITIVE_ERROR, invalid.message, text)
        }
    }

    @Test
    fun `the sign is checked after the rounding`() {
        // 0,0004 rounds to 0.000 — such a price would cost every trip 0,00 zl.
        val tooSmall = assertIs<DefaultPriceCheck.Invalid>(parseDefaultPrice("0,0004"))
        assertEquals(Texts.REFUEL_POSITIVE_ERROR, tooSmall.message)

        val smallest = assertIs<DefaultPriceCheck.Valid>(parseDefaultPrice("0,0005"))
        assertEquals(0, BigDecimal("0.001").compareTo(smallest.price), "HALF_UP do 0,001")
    }

}
