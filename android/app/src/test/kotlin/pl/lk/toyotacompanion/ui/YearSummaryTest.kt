package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun drive(id: String, start: String, km: Double, fuel: String, price: String? = null, ev: Double? = null): StoredTrip {
    val begin = Instant.parse(start)
    val record = TripRecord(Trip(id, begin, begin.plusSeconds(1_800), km, BigDecimal(fuel), ev), "VINAAA111")
    val cost = price?.let { TripCost(id, BigDecimal(it), null, null, CostSource.REFUEL) }
    return StoredTrip(record, cost)
}

private val TRIPS = listOf(
    drive("a", "2025-05-10T08:00:00Z", 100.0, "4.0", "25.00", ev = 40.0),
    drive("b", "2025-06-10T08:00:00Z", 100.0, "6.0", "40.00"),
    drive("c", "2026-05-10T08:00:00Z", 100.0, "4.5", "30.00", ev = 50.0),
    drive("d", "2026-05-11T08:00:00Z", 250.0, "10.0", "70.00"),
    drive("e", "2026-06-10T08:00:00Z", 100.0, "6.0", null),
    drive("f", "2026-07-01T08:00:00Z", 20.0, "1.0", "6.00"),
)

/** docs/SPEC.md §29: one year on one page and its text. */
class YearSummaryTest {
    @Test
    fun `the years with trips come newest first`() {
        assertEquals(listOf(2026, 2025), yearsWithTrips(TRIPS, WARSAW))
        assertEquals(emptyList(), yearsWithTrips(emptyList(), WARSAW))
    }

    @Test
    fun `totals, average and money of a year`() {
        val s = requireNotNull(yearSummary(TRIPS, 2026, WARSAW))
        assertEquals(4, s.trips)
        assertEquals(BigDecimal("470.0"), s.distanceKm)
        assertEquals(BigDecimal("21.5"), s.fuelL)
        assertEquals(BigDecimal("4.57"), s.lPer100)
        assertEquals(BigDecimal("106.00"), s.costPln)
        assertEquals(1, s.tripsWithoutCost)
        assertEquals(BigDecimal("28.65"), s.costPer100)
        assertEquals(BigDecimal("11"), s.evSharePct)
        assertEquals(4, s.daysDriven)
        assertEquals(BigDecimal("250.0"), s.longestTripKm)
    }

    @Test
    fun `best, worst and dearest month`() {
        val s = requireNotNull(yearSummary(TRIPS, 2026, WARSAW))
        assertEquals(MonthKey(2026, 5), s.bestMonth?.month)
        assertEquals(MonthKey(2026, 6), s.worstMonth?.month)
        assertEquals(MonthKey(2026, 5), s.dearestMonth?.month)
    }

    @Test
    fun `the co2 of a year comes from the unrounded litres`() {
        val s = requireNotNull(yearSummary(TRIPS, 2026, WARSAW))
        // 21.5 l * 2.3 = 49.45 kg -> 49.5 kg.
        assertEquals(BigDecimal("49.5"), s.co2Kg)
        // 0.333 l * 3 trips: the litres shown are 1.0 (rounded), the CO2 is taken from 0.999 l -> 2.3 kg, not from 1.0 l.
        val small = listOf(
            drive("a", "2026-05-10T08:00:00Z", 10.0, "0.333"),
            drive("b", "2026-05-11T08:00:00Z", 10.0, "0.333"),
            drive("c", "2026-05-12T08:00:00Z", 10.0, "0.333"),
        )
        assertEquals(BigDecimal("2.3"), requireNotNull(yearSummary(small, 2026, WARSAW)).co2Kg)
    }

    @Test
    fun `the co2 line follows the cost and is in the share text`() {
        val now = requireNotNull(yearSummary(TRIPS, 2026, WARSAW))
        val lines = yearSummaryLines(now, null, WARSAW)
        assertEquals("Ślad CO₂: 49,5\u00A0kg", lines[3])
        assertTrue(yearSummaryShareText(now, null, WARSAW).contains("\nŚlad CO₂: 49,5\u00A0kg\n"))
    }

    @Test
    fun `a year without trips has no summary and without a price no amount`() {
        assertNull(yearSummary(TRIPS, 2024, WARSAW))
        val unpriced = requireNotNull(yearSummary(listOf(drive("a", "2026-05-10T08:00:00Z", 80.0, "4.0")), 2026, WARSAW))
        assertNull(unpriced.costPln)
        assertNull(unpriced.costPer100)
        assertNull(unpriced.dearestMonth)
        assertEquals("Koszt: brak ceny", yearSummaryLines(unpriced, null, WARSAW)[2])
    }

    @Test
    fun `the lines read in Polish and compare with the year before`() {
        val now = requireNotNull(yearSummary(TRIPS, 2026, WARSAW))
        val before = requireNotNull(yearSummary(TRIPS, 2025, WARSAW))
        val lines = yearSummaryLines(now, before, WARSAW)
        assertEquals("Trasy: 4 · 470 km · 22 l paliwa", lines[0])
        assertEquals("Średnie spalanie: 4,57 l/100 km", lines[1])
        assertTrue(lines[2].startsWith("Koszt paliwa: 106,00 zł (28,65 zł/100 km)"))
        assertTrue(lines.last().startsWith("Wobec 2025: km +135 %"))
        assertTrue(lines.any { it.startsWith("Najdłuższa trasa: 250,0 km") })
        assertTrue(yearSummaryShareText(now, before, WARSAW).startsWith("Toyota Companion – podsumowanie roku 2026\n"))
    }

    @Test
    fun `a share text carries no position and no vin`() {
        val text = yearSummaryShareText(requireNotNull(yearSummary(TRIPS, 2026, WARSAW)), null, WARSAW)
        assertTrue(!text.contains("VIN", ignoreCase = true) && !text.contains("54.3"))
    }

    @Test
    fun `percent change needs both sides and a positive base`() {
        assertEquals("+50 %", percentChange(BigDecimal("150"), BigDecimal("100")))
        assertEquals("-25 %", percentChange(BigDecimal("75"), BigDecimal("100")))
        assertEquals("0 %", percentChange(BigDecimal("100"), BigDecimal("100")))
        assertNull(percentChange(null, BigDecimal("100")))
        assertNull(percentChange(BigDecimal("1"), BigDecimal.ZERO))
    }

    @Test
    fun `the controller opens on the newest year, switches and resets`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val c = YearSummaryController({ TRIPS }, { WARSAW }, CoroutineScope(SupervisorJob() + io), io)
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(2026, c.state.value.selected)
        assertEquals(2025, c.state.value.previous?.year)
        c.load(2025)
        advanceUntilIdle()
        assertEquals(2025, c.state.value.selected)
        assertNull(c.state.value.previous)
        c.load(1999)
        advanceUntilIdle()
        assertEquals(2026, c.state.value.selected, "an unknown year falls back to the newest")
        c.reset()
        assertTrue(c.state.value.loading)
    }

    @Test
    fun `a failed read is shown as a failed read`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val c = YearSummaryController({ throw IllegalStateException("db") }, { WARSAW }, CoroutineScope(SupervisorJob() + io), io)
        c.load()
        advanceUntilIdle()
        assertTrue(c.state.value.loadFailed)
    }
}
