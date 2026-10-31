package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Test

private val UTC: ZoneId = ZoneOffset.UTC

/** A zone with a real offset, so a UTC-only test would not prove anything. */
private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/**
 * Formatting of a refuel row (docs/SPEC.md §11.2). The thousand separator and the
 * decimal comma are built by hand, so the last test repeats the assertions under
 * other default locales.
 */
class RefuelFormattingTest {
    @Test
    fun `time is shown in the given zone`() {
        val ts = Instant.parse("2026-10-29T22:05:00Z")

        assertEquals("29.10 22:05", RefuelFormatting.time(ts, UTC))
        // DST ended on 25 October 2026, so 22:05 UTC on 29 October is 23:05 local (CET = UTC+1).
        assertEquals("29.10 23:05", RefuelFormatting.time(ts, WARSAW))
    }

    @Test
    fun `level jump shows the level before and after`() {
        assertEquals("20% \u2192 80%", RefuelFormatting.levelJump(20, 80))
    }

    @Test
    fun `odometer groups thousands with a plain space`() {
        assertEquals("0 km", RefuelFormatting.odometer(0.0))
        assertEquals("999 km", RefuelFormatting.odometer(999.4))
        assertEquals("1 000 km", RefuelFormatting.odometer(999.5))
        assertEquals("12 345 km", RefuelFormatting.odometer(12_345.0))
        assertEquals("1 234 567 km", RefuelFormatting.odometer(1_234_567.4))
    }

    @Test
    fun `odometer rounds half up to whole kilometres`() {
        assertEquals("12 346 km", RefuelFormatting.odometer(12_345.5))
        assertEquals("12 345 km", RefuelFormatting.odometer(12_345.49))
    }

    @Test
    fun `liters input keeps the domain precision without trailing zeros`() {
        assertEquals("14,19", RefuelFormatting.litersInput(BigDecimal("14.190")))
        assertEquals("25,8", RefuelFormatting.litersInput(BigDecimal("25.800")))
        assertEquals("0,003", RefuelFormatting.litersInput(BigDecimal("0.003")))
        // stripTrailingZeros may push a round number into exponent notation; the field
        // must still show plain digits (14,19 l of an estimate can be a whole 30 l).
        assertEquals("30", RefuelFormatting.litersInput(BigDecimal("30.000")))
        assertEquals("1000", RefuelFormatting.litersInput(BigDecimal("1000.000")))
        assertEquals("43", RefuelFormatting.litersInput(BigDecimal("43")))
    }

    @Test
    fun `liters for reading carry the unit`() {
        assertEquals("14,19 l", RefuelFormatting.liters(BigDecimal("14.190")))
        assertEquals("30 l", RefuelFormatting.liters(BigDecimal("30.000")))
    }

    @Test
    fun `formatting does not depend on the default locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.FRANCE, Locale.GERMANY)) {
                Locale.setDefault(locale)
                assertEquals("12 345 km", RefuelFormatting.odometer(12_345.0), locale.toString())
                assertEquals("20% \u2192 80%", RefuelFormatting.levelJump(20, 80), locale.toString())
                assertEquals("14,19", RefuelFormatting.litersInput(BigDecimal("14.190")), locale.toString())
                assertEquals("14,19 l", RefuelFormatting.liters(BigDecimal("14.190")), locale.toString())
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
