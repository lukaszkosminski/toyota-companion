package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Amount parsing and the combination check of the price form (docs/SPEC.md §11.2).
 * The rules mirror docs/SPEC.md §2 on purpose, so the screen can speak Polish;
 * these tests are what keeps the copy honest.
 */
class RefuelFormTest {
    @Test
    fun `comma and dot are the same separator`() {
        assertAmount("6.19", "6.19")
        assertAmount("6,19", "6.19")
        assertAmount(" 6,190 ", "6.190")
        assertAmount("43", "43")
    }

    @Test
    fun `an empty field means the domain derives that value`() {
        assertIs<RefuelAmount.Missing>(parseRefuelAmount(""))
        assertIs<RefuelAmount.Missing>(parseRefuelAmount("   "))
    }

    @Test
    fun `text that is not a plain decimal is refused`() {
        for (text in listOf("6,1,9", "abc", "6,", ",9", "1e3", "6 . 19", "--5")) {
            assertIs<RefuelAmount.NotANumber>(parseRefuelAmount(text), text)
        }
    }

    @Test
    fun `the four combinations of docs SPEC 2 are accepted`() {
        val price = assertIs<RefuelFormCheck.Valid>(checkRefuelForm("6,19", "", ""))
        assertEquals(0, BigDecimal("6.19").compareTo(requireNotNull(price.price)))
        assertNull(price.liters)
        assertNull(price.total)

        val litersAndTotal = assertIs<RefuelFormCheck.Valid>(checkRefuelForm("", "25,8", "159,67"))
        assertNull(litersAndTotal.price)
        assertEquals(0, BigDecimal("25.8").compareTo(requireNotNull(litersAndTotal.liters)))

        assertIs<RefuelFormCheck.Valid>(checkRefuelForm("6,19", "25,8", ""))
        assertIs<RefuelFormCheck.Valid>(checkRefuelForm("6,19", "", "159,67"))
    }

    @Test
    fun `any other combination is refused`() {
        for ((price, liters, total) in listOf(
            Triple("", "", ""),
            Triple("", "25,8", ""),
            Triple("", "", "159,67"),
            Triple("6,19", "25,8", "159,67"),
        )) {
            val check = assertIs<RefuelFormCheck.Invalid>(
                checkRefuelForm(price, liters, total),
                "$price/$liters/$total",
            )
            assertEquals(Texts.REFUEL_COMBINATION_ERROR, check.message)
            assertTrue(check.errors.isEmpty(), "$price/$liters/$total has no broken field")
        }
    }

    @Test
    fun `values that are not positive name their field`() {
        val zero = assertIs<RefuelFormCheck.Invalid>(checkRefuelForm("0", "", ""))
        assertEquals(Texts.REFUEL_POSITIVE_ERROR, zero.message)
        assertEquals(listOf(RefuelField.Price), zero.errors.map { it.field })

        val negative = assertIs<RefuelFormCheck.Invalid>(checkRefuelForm("", "-5", ""))
        assertEquals(listOf(RefuelField.Liters), negative.errors.map { it.field })
    }

    @Test
    fun `the first broken field is what the error card shows`() {
        val check = assertIs<RefuelFormCheck.Invalid>(checkRefuelForm("abc", "25,8", ""))

        assertEquals(Texts.REFUEL_NUMBER_ERROR, check.message)
        assertEquals(listOf(RefuelField.Price), check.errors.map { it.field })
    }

    private fun assertAmount(input: String, expected: String) {
        val given = assertIs<RefuelAmount.Given>(parseRefuelAmount(input), input)
        assertEquals(0, BigDecimal(expected).compareTo(given.value), input)
    }
}
