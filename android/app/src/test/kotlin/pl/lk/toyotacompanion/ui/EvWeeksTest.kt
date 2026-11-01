package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun drive(id: String, start: String, km: Double, ev: Double?, evTimeS: Long? = null): StoredTrip {
    val begin = Instant.parse(start)
    return StoredTrip(
        TripRecord(Trip(id, begin, begin.plusSeconds(1_800), km, BigDecimal("0.5"), ev), "VINAAA111", hdcEvTimeS = evTimeS),
        null,
    )
}

/** docs/SPEC.md §36: weekly driving on the battery from the trips. */
class EvWeeksTest {
    // 2026-10-26 is a Monday.
    private val trips = listOf(
        drive("a", "2026-10-26T08:00:00Z", 10.0, 6.0, 600),
        drive("b", "2026-10-30T08:00:00Z", 30.0, 30.0, 1_800),
        drive("c", "2026-11-01T10:00:00Z", 10.0, null),
        drive("d", "2026-11-02T08:00:00Z", 20.0, 5.0, 300),
    )

    @Test
    fun `weeks start on Monday and are listed oldest first`() {
        val weeks = evByWeek(trips, WARSAW)
        assertEquals(listOf(LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-02")), weeks.map { it.weekStart })
    }

    @Test
    fun `a Sunday trip belongs to the week before and the local date decides`() {
        // 23:30 UTC on Sunday 1.11 is already Monday 2.11 in Warsaw (UTC+1).
        val late = listOf(drive("x", "2026-11-01T23:30:00Z", 10.0, 5.0))
        assertEquals(LocalDate.parse("2026-11-02"), evByWeek(late, WARSAW).single().weekStart)
        val noon = listOf(drive("y", "2026-11-01T11:00:00Z", 10.0, 5.0))
        assertEquals(LocalDate.parse("2026-10-26"), evByWeek(noon, WARSAW).single().weekStart)
    }

    @Test
    fun `km, share and minutes of a week`() {
        val first = evByWeek(trips, WARSAW).first()
        assertEquals(3, first.trips)
        assertEquals(BigDecimal("50.0"), first.distanceKm)
        assertEquals(BigDecimal("36.0"), first.evKm)
        assertEquals(BigDecimal("72"), first.evSharePct)
        assertEquals(40L, first.evMinutes)
    }

    @Test
    fun `a trip without electric data adds only its km and a week without any time has no minutes`() {
        val only = evByWeek(listOf(drive("a", "2026-10-26T08:00:00Z", 10.0, null)), WARSAW).single()
        assertEquals(BigDecimal("0"), only.evSharePct)
        assertNull(only.evMinutes)
    }

    @Test
    fun `the electric part is capped by the trips own distance`() {
        assertEquals(BigDecimal("100"), evByWeek(listOf(drive("a", "2026-10-26T08:00:00Z", 10.0, 10.4)), WARSAW).single().evSharePct)
    }

    @Test
    fun `broken trips are left out and only the newest weeks are kept`() {
        assertEquals(emptyList(), evByWeek(listOf(drive("a", "2026-10-26T08:00:00Z", 0.0, 0.0), drive("b", "2026-10-26T08:00:00Z", Double.NaN, 1.0)), WARSAW))
        val many = (0 until 15).map { drive("w$it", Instant.parse("2026-01-05T08:00:00Z").plusSeconds(it * 7L * 86_400).toString(), 10.0, 5.0) }
        val weeks = evByWeek(many, WARSAW)
        assertEquals(12, weeks.size)
        assertEquals(LocalDate.parse("2026-01-26"), weeks.first().weekStart)
    }

    @Test
    fun `the text names the week, the share, the km and the minutes`() {
        val week = evByWeek(trips, WARSAW).first()
        assertEquals("26.10", EvWeeksFormatting.weekLabel(week))
        assertEquals("26.10: 72 % na prądzie · 36,0 z 50,0 km · 40 min na prądzie · trasy: 3", EvWeeksFormatting.line(week))
        assertTrue(Texts.EV_WEEKS_HINT.contains("12 V"))
    }
}
