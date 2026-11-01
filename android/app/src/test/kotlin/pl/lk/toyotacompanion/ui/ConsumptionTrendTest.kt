package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/** One trip of [km] kilometres burning [l100] litres per 100 km, started on [start]. */
private fun ride(id: String, start: String, km: Double, l100: Double): StoredTrip {
    val begin = Instant.parse(start)
    val fuel = BigDecimal(km * l100 / 100.0).setScale(3, java.math.RoundingMode.HALF_UP)
    return StoredTrip(TripRecord(Trip(id, begin, begin.plusSeconds(1_800), km, fuel, null), "VINAAA111"), null)
}

/** One 100 km month starting on the 10th of [year]-[month]. */
private fun month(year: Int, month: Int, l100: Double): StoredTrip =
    ride("m$year-$month", "$year-${month.toString().padStart(2, '0')}-10T10:00:00Z", 100.0, l100)

/** docs/SPEC.md §24: whole-period average, monthly bars and the direction of the bars. */
class ConsumptionTrendTest {
    @Test
    fun `the overall average is total fuel over total distance`() {
        val view = consumptionView(listOf(ride("a", "2026-10-01T08:00:00Z", 10.0, 5.0), ride("b", "2026-10-02T08:00:00Z", 30.0, 4.0)), WARSAW)
        assertEquals(BigDecimal("4.25"), view.overall)
        assertEquals(BigDecimal("40.0"), view.overallKm)
    }

    @Test
    fun `no trips give no average and no bars`() {
        val view = consumptionView(emptyList(), WARSAW)
        assertNull(view.overall)
        assertEquals(emptyList(), view.months)
        assertNull(view.trend)
    }

    @Test
    fun `a month is a bar from fifty kilometres on`() {
        val view = consumptionView(
            listOf(ride("a", "2026-09-05T08:00:00Z", 49.9, 5.0), ride("b", "2026-10-05T08:00:00Z", 50.0, 5.0)),
            WARSAW,
        )
        assertEquals(listOf(MonthKey(2026, 10)), view.months.map { it.month })
    }

    @Test
    fun `a trip belongs to the month of its local start`() {
        // 23:30 UTC on 31.10 is 1.11 in Warsaw.
        val view = consumptionView(listOf(ride("a", "2026-10-31T23:30:00Z", 80.0, 5.0)), WARSAW)
        assertEquals(MonthKey(2026, 11), view.months.single().month)
    }

    @Test
    fun `bars are oldest first and at most twelve`() {
        val trips = (1..14).map { month(2025 + (it - 1) / 12, (it - 1) % 12 + 1, 5.0) }
        val view = consumptionView(trips, WARSAW)
        assertEquals(12, view.months.size)
        assertEquals(MonthKey(2025, 3), view.months.first().month)
        assertEquals(MonthKey(2026, 2), view.months.last().month)
    }

    @Test
    fun `rising bars are a rising trend`() {
        val view = consumptionView(listOf(month(2026, 5, 4.0), month(2026, 6, 4.4), month(2026, 7, 4.8), month(2026, 8, 5.2)), WARSAW)
        assertEquals(ConsumptionTrend.RISING, view.trend)
        assertTrue(view.changePct!!.signum() > 0)
    }

    @Test
    fun `falling bars are a falling trend`() {
        val view = consumptionView(listOf(month(2026, 5, 5.2), month(2026, 6, 4.8), month(2026, 7, 4.4), month(2026, 8, 4.0)), WARSAW)
        assertEquals(ConsumptionTrend.FALLING, view.trend)
        assertTrue(view.changePct!!.signum() < 0)
    }

    @Test
    fun `nearly flat bars are steady`() {
        val view = consumptionView(listOf(month(2026, 5, 4.50), month(2026, 6, 4.52), month(2026, 7, 4.48), month(2026, 8, 4.51)), WARSAW)
        assertEquals(ConsumptionTrend.STEADY, view.trend)
    }

    @Test
    fun `fewer than three bars give no trend`() {
        val view = consumptionView(listOf(month(2026, 7, 4.0), month(2026, 8, 6.0)), WARSAW)
        assertNull(view.trend)
        assertNull(view.changePct)
    }

    @Test
    fun `a gap in the months is measured on the calendar, not on the bar count`() {
        // 4.0 in January, then 6.0 in May and 6.0 in June: steeper than if the months were adjacent.
        val gapped = consumptionView(listOf(month(2026, 1, 4.0), month(2026, 5, 6.0), month(2026, 6, 6.0)), WARSAW)
        assertEquals(ConsumptionTrend.RISING, gapped.trend)
    }

    @Test
    fun `broken trips are left out`() {
        val bad = StoredTrip(TripRecord(Trip("x", Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T09:00:00Z"), Double.NaN, BigDecimal("1"), null), "V"), null)
        val zero = ride("z", "2026-10-02T08:00:00Z", 0.0, 5.0)
        val view = consumptionView(listOf(bad, zero, ride("a", "2026-10-03T08:00:00Z", 100.0, 5.0)), WARSAW)
        assertEquals(BigDecimal("5.00"), view.overall)
    }

    @Test
    fun `the trend line has an arrow and a sign`() {
        val rising = consumptionView(listOf(month(2026, 5, 4.0), month(2026, 6, 4.4), month(2026, 7, 4.8), month(2026, 8, 5.2)), WARSAW)
        assertTrue(ConsumptionFormatting.trendLine(rising).orEmpty().startsWith("Trend: ↗ rosnące (+"))
        val falling = consumptionView(listOf(month(2026, 5, 5.2), month(2026, 6, 4.8), month(2026, 7, 4.4), month(2026, 8, 4.0)), WARSAW)
        assertTrue(ConsumptionFormatting.trendLine(falling).orEmpty().startsWith("Trend: ↘ malejące (-"))
        assertNull(ConsumptionFormatting.trendLine(consumptionView(emptyList(), WARSAW)))
    }

    @Test
    fun `the overall line and the month labels are Polish`() {
        val view = consumptionView(listOf(ride("a", "2026-10-01T08:00:00Z", 100.0, 4.123)), WARSAW)
        assertEquals("Średnio z całego okresu: 4,12 l/100 km (100 km)", ConsumptionFormatting.overallLine(view))
        assertEquals("paź", ConsumptionFormatting.monthShort(MonthKey(2026, 10)))
        assertEquals("sty 27", ConsumptionFormatting.monthShort(MonthKey(2027, 1)))
    }

    @Test
    fun `the axis starts under the lowest month so differences show`() {
        val view = consumptionView(listOf(month(2026, 6, 3.8), month(2026, 7, 4.5)), WARSAW)
        val base = ConsumptionFormatting.axisBase(view.months)
        assertEquals(BigDecimal("2.6"), base)
        val top = view.months.maxOf { it.lPer100 }
        assertEquals(1f, ConsumptionFormatting.barShare(top, top, base))
        assertTrue(ConsumptionFormatting.barShare(BigDecimal("3.8"), top, base) < 0.7f)
    }

    @Test
    fun `one month or equal months start the axis at zero`() {
        assertEquals(BigDecimal.ZERO, ConsumptionFormatting.axisBase(consumptionView(listOf(month(2026, 6, 4.0)), WARSAW).months))
        assertEquals(BigDecimal.ZERO, ConsumptionFormatting.axisBase(consumptionView(listOf(month(2026, 6, 4.0), month(2026, 7, 4.0)), WARSAW).months))
    }
}
