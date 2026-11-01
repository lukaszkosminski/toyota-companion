package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** docs/SPEC.md §30: the declared consumption typed by the owner, kept in the settings, set against the own average. */
class DeclaredConsumptionTest {
    @Test
    fun `a comma or a dot, rounded to two decimals`() {
        assertEquals(DeclaredCheck.Valid(BigDecimal("4.50")), parseDeclaredConsumption("4,5"))
        assertEquals(DeclaredCheck.Valid(BigDecimal("4.53")), parseDeclaredConsumption(" 4.526 "))
        assertEquals(DeclaredCheck.Valid(BigDecimal("20.00")), parseDeclaredConsumption("20"))
    }

    @Test
    fun `empty removes, anything else wrong is invalid and never clamped`() {
        assertEquals(DeclaredCheck.Empty, parseDeclaredConsumption("  "))
        for (bad in listOf("0", "-4", "20,01", "abc", "4,5 l", "NaN", "1e2")) {
            assertEquals(DeclaredCheck.Invalid, parseDeclaredConsumption(bad), bad)
        }
    }

    @Test
    fun `the comparison names the direction`() {
        assertEquals(
            "Deklarowane: 4,50 l/100 km · Twoje średnie: -8 % (taniej niż deklarowane)",
            declaredComparison(BigDecimal("4.13"), BigDecimal("4.50")),
        )
        assertEquals(
            "Deklarowane: 4,00 l/100 km · Twoje średnie: +10 % (drożej niż deklarowane)",
            declaredComparison(BigDecimal("4.40"), BigDecimal("4.00")),
        )
        assertTrue(declaredComparison(BigDecimal("4.00"), BigDecimal("4.00")).orEmpty().contains("tak samo"))
        assertNull(declaredComparison(null, BigDecimal("4.5")))
        assertNull(declaredComparison(BigDecimal("4.5"), null))
    }

    @Test
    fun `the controller loads once, saves a valid value and refuses an invalid one`() {
        var stored: BigDecimal? = BigDecimal("4.10")
        var reads = 0
        val c = DeclaredConsumptionController(read = { reads++; stored }, save = { stored = it })
        c.ensureLoaded()
        c.ensureLoaded()
        assertEquals(1, reads)
        assertEquals(BigDecimal("4.10"), c.state.value)
        assertTrue(c.set("4,6"))
        assertEquals(BigDecimal("4.60"), stored)
        assertEquals(BigDecimal("4.60"), c.state.value)
        assertFalse(c.set("żółw"))
        assertEquals(BigDecimal("4.60"), stored)
        assertTrue(c.set(""))
        assertNull(stored)
        assertNull(c.state.value)
    }
}
