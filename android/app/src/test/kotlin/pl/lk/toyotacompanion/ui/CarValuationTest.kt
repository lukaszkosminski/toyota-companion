package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The car valuation form and its texts (PRD F55, docs/SPEC.md §55). */
class CarValuationTest {

    private var previousLanguage = AppLanguage.PL

    @Before
    fun rememberLanguage() {
        previousLanguage = Lang.current
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }

    @Test
    fun `amounts accept comma, whitespace and a zero value`() {
        val check = parseCarValuation("100 000,50", LocalDate.of(2025, 1, 1), "0", LocalDate.of(2026, 1, 1))
        val valid = assertIs<CarValuationCheck.Valid>(check)
        assertEquals(0, BigDecimal("100000.50").compareTo(valid.valuation.purchasePln))
        assertEquals(0, BigDecimal.ZERO.compareTo(valid.valuation.valuePln))
        assertEquals(LocalDate.of(2025, 1, 1), valid.valuation.purchaseDate)
        assertEquals(LocalDate.of(2026, 1, 1), valid.valuation.valueDate)
    }

    @Test
    fun `empty amounts mean no valuation`() {
        assertIs<CarValuationCheck.Empty>(parseCarValuation("", LocalDate.of(2025, 1, 1), "  ", LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `a price of zero and a negative value are refused`() {
        val zeroPrice = assertIs<CarValuationCheck.Invalid>(
            parseCarValuation("0", LocalDate.of(2025, 1, 1), "100", LocalDate.of(2026, 1, 1)),
        )
        assertEquals(Texts.CAR_PURCHASE_INVALID, zeroPrice.message)
        val negative = assertIs<CarValuationCheck.Invalid>(
            parseCarValuation("100", LocalDate.of(2025, 1, 1), "-1", LocalDate.of(2026, 1, 1)),
        )
        assertEquals(Texts.CAR_VALUE_INVALID, negative.message)
        val noValue = assertIs<CarValuationCheck.Invalid>(
            parseCarValuation("100", LocalDate.of(2025, 1, 1), "", LocalDate.of(2026, 1, 1)),
        )
        assertEquals(Texts.CAR_VALUE_INVALID, noValue.message)
    }

    @Test
    fun `the valuation cannot be older than the purchase`() {
        val check = parseCarValuation("100", LocalDate.of(2025, 1, 1), "80", LocalDate.of(2024, 12, 31))
        assertEquals(Texts.CAR_VALUE_DATE_ORDER, assertIs<CarValuationCheck.Invalid>(check).message)
    }

    @Test
    fun `the stale hint carries the date and points to options`() {
        val polish = Texts.ownershipValuationStale("01.06.2025")
        assertTrue("01.06.2025" in polish && "Opcjach" in polish, polish)

        Lang.current = AppLanguage.EN
        val english = Texts.ownershipValuationStale("01.06.2025")
        assertTrue("01.06.2025" in english && "Options" in english, english)
        assertTrue("estimate" in Texts.ownershipValueLoss("1,00 zł", null))
        assertTrue("depreciation" in Texts.ownershipWithLoss("2,00 zł", null).lowercase())
    }

    @Test
    fun `the valuation date is printed day dot month dot year`() {
        assertEquals("01.06.2025", TripsFormatting.date(LocalDate.of(2025, 6, 1)))
    }
}
