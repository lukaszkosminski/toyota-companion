package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.LitersSource

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

    @Test
    fun `an accepted estimate next to a price is written as ESTIMATE`() {
        val valid = assertIs<RefuelFormCheck.Valid>(checkRefuelForm("6,50", "14,19", "", litersFromEstimate = true))

        assertEquals(LitersSource.ESTIMATE, valid.litersSource, "docs/SPEC.md §11.2, PRD F2a")
        assertEquals(0, BigDecimal("14.19").compareTo(requireNotNull(valid.liters)))
    }

    @Test
    fun `liters typed by hand carry no source`() {
        val valid = assertIs<RefuelFormCheck.Valid>(checkRefuelForm("6,50", "14,19", ""))

        assertNull(valid.litersSource, "the domain records RECEIPT (docs/SPEC.md §2)")
    }

    @Test
    fun `an estimate with a total and no price is an error of the price field`() {
        val invalid = assertIs<RefuelFormCheck.Invalid>(
            checkRefuelForm("", "14,19", "92,24", litersFromEstimate = true),
        )

        assertEquals(Texts.REFUEL_ESTIMATE_NEEDS_PRICE, invalid.message)
        assertEquals(listOf(RefuelField.Price), invalid.errors.map { it.field }, "the note sits under the price")
        assertEquals(Texts.REFUEL_ESTIMATE_NEEDS_PRICE, invalid.errors.first().message)
    }

    @Test
    fun `the estimate flag may not mark liters the domain derives`() {
        // Price and total: the domain would count the liters itself, so ESTIMATE would
        // describe its own arithmetic instead of the sensor (docs/SPEC.md §2).
        val valid = assertIs<RefuelFormCheck.Valid>(checkRefuelForm("6,50", "", "92,24", litersFromEstimate = true))

        assertNull(valid.litersSource, "the domain records DERIVED")
    }

    @Test
    fun `a price alone is still a price alone with the flag on`() {
        val valid = assertIs<RefuelFormCheck.Valid>(checkRefuelForm("6,50", "", "", litersFromEstimate = true))

        assertNull(valid.liters, "§6 counts the estimate on its own; nothing is stored here")
        assertNull(valid.litersSource)
    }

    @Test
    fun `liters and total without the flag are still the second combination`() {
        val valid = assertIs<RefuelFormCheck.Valid>(checkRefuelForm("", "25,8", "159,67"))

        assertNull(valid.litersSource, "D3 guards an accepted estimate only")
        assertEquals(0, BigDecimal("25.8").compareTo(requireNotNull(valid.liters)))
    }

    private fun assertAmount(input: String, expected: String) {
        val given = assertIs<RefuelAmount.Given>(parseRefuelAmount(input), input)
        assertEquals(0, BigDecimal(expected).compareTo(given.value), input)
    }
}
