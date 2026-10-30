package pl.lk.toyotacompanion.ui

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
    fun `formatting does not depend on the default locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.FRANCE, Locale.GERMANY)) {
                Locale.setDefault(locale)
                assertEquals("12 345 km", RefuelFormatting.odometer(12_345.0), locale.toString())
                assertEquals("20% \u2192 80%", RefuelFormatting.levelJump(20, 80), locale.toString())
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
