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

private fun priced(id: String, start: String, km: Double, price: String?, source: pl.lk.toyotacompanion.core.domain.CostSource = pl.lk.toyotacompanion.core.domain.CostSource.REFUEL): StoredTrip {
    val begin = Instant.parse(start)
    val record = TripRecord(Trip(id, begin, begin.plusSeconds(1_800), km, BigDecimal("1.0"), null), "VINAAA111")
    val cost = price?.let { pl.lk.toyotacompanion.core.domain.TripCost(id, BigDecimal(it), null, null, source) }
    return StoredTrip(record, cost)
}

/** docs/SPEC.md §24: the money of each month and of the whole archive. */
class MonthlyCostsTest {
    @Test
    fun `a month sums the costs of its priced trips and counts the unpriced ones`() {
        val view = consumptionView(
            listOf(
                priced("a", "2026-10-02T08:00:00Z", 10.0, "4.00"),
                priced("b", "2026-10-03T08:00:00Z", 30.0, "12.50"),
                priced("c", "2026-10-04T08:00:00Z", 20.0, null),
                priced("d", "2026-09-04T08:00:00Z", 10.0, "3.00"),
            ),
            WARSAW,
        )
        assertEquals(listOf(MonthKey(2026, 9), MonthKey(2026, 10)), view.monthCosts.map { it.month })
        val oct = view.monthCosts.last()
        assertEquals(BigDecimal("16.50"), oct.costPln)
        assertEquals(1, oct.tripsWithoutCost)
        assertEquals(3, oct.trips)
        assertEquals(BigDecimal("60.0"), oct.distanceKm)
        assertEquals(BigDecimal("41.25"), oct.costPer100)
        assertEquals(BigDecimal("19.50"), view.totalCost)
        assertEquals(1, view.totalTripsWithoutCost)
    }

    @Test
    fun `a month without any price has no amount, never zero`() {
        val view = consumptionView(listOf(priced("a", "2026-10-02T08:00:00Z", 10.0, null)), WARSAW)
        assertNull(view.monthCosts.single().costPln)
        assertNull(view.monthCosts.single().costPer100)
        assertNull(view.totalCost)
        assertEquals("–", ConsumptionFormatting.costLabel(view.monthCosts.single()))
        assertNull(ConsumptionFormatting.totalCostLine(view))
    }

    @Test
    fun `a trip whose source is none counts as unpriced even with an amount`() {
        val view = consumptionView(
            listOf(priced("a", "2026-10-02T08:00:00Z", 10.0, "5.00", pl.lk.toyotacompanion.core.domain.CostSource.NONE)),
            WARSAW,
        )
        assertNull(view.monthCosts.single().costPln)
        assertEquals(1, view.monthCosts.single().tripsWithoutCost)
    }

    @Test
    fun `short months are listed too and only the newest twelve are kept`() {
        val trips = (1..14).map { priced("m$it", "${2025 + (it - 1) / 12}-${((it - 1) % 12 + 1).toString().padStart(2, '0')}-10T10:00:00Z", 5.0, "2.00") }
        val view = consumptionView(trips, WARSAW)
        assertEquals(12, view.monthCosts.size)
        assertEquals(MonthKey(2025, 3), view.monthCosts.first().month)
    }

    @Test
    fun `the lines read in Polish`() {
        val view = consumptionView(
            listOf(priced("a", "2026-10-02T08:00:00Z", 10.0, "4.00"), priced("b", "2026-10-03T08:00:00Z", 10.0, null)),
            WARSAW,
        )
        val month = view.monthCosts.single()
        assertEquals("4", ConsumptionFormatting.costLabel(month))
        assertEquals("paź: 4,00 zł · 20,0 km · 40,00 zł/100 km · bez ceny: 1", ConsumptionFormatting.costLine(month).replace("/100 km", "/100 km"))
        assertEquals("Razem z całego okresu: 4,00 zł · bez ceny: 1", ConsumptionFormatting.totalCostLine(view))
    }
}
