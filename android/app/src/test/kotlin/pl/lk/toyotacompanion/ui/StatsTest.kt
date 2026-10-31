package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.PeriodStats
import pl.lk.toyotacompanion.core.domain.Season
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost
import pl.lk.toyotacompanion.core.domain.TripShare

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun stored(id: String, start: String, km: Double = 10.0, ev: Double? = 4.0, cost: String? = "3.10"): StoredTrip {
    val begin = Instant.parse(start)
    val trip = Trip(id, begin, begin.plusSeconds(1_800), km, BigDecimal("0.500"), ev)
    return StoredTrip(
        TripRecord(trip = trip, vin = "VIN"),
        cost?.let { TripCost(id, BigDecimal(it), BigDecimal("6.190"), null, CostSource.REFUEL) },
    )
}

private fun period(year: Int, season: Season?, cost: String? = "312.40", ev: String? = "41.0") = PeriodStats(
    year = year,
    season = season,
    trips = 12,
    distanceKm = BigDecimal("450.0"),
    fuelL = BigDecimal("20.250"),
    lPer100Km = BigDecimal("4.50"),
    costPln = cost?.let { BigDecimal(it) },
    tripsWithoutCost = 0,
    costPer100Km = cost?.let { BigDecimal("69.42") },
    evSharePct = ev?.let { BigDecimal(it) },
    averageTripKm = BigDecimal("37.5"),
)

/** docs/SPEC.md §16-§18: the Polish text of the statistics and its controller. */
class StatsTest {

    @Test
    fun `a missing electric share says so and is never zero`() {
        assertEquals(Texts.STATS_NO_EV_DATA, StatsFormatting.share(null))
        assertEquals("61,3 %", StatsFormatting.share(BigDecimal("61.3")))
        assertEquals("0,0 %", StatsFormatting.share(BigDecimal("0.0")))
    }

    @Test
    fun `a ranking row shows the share, the distance and a clipped mark`() {
        val row = TripShare("t", BigDecimal("12.0"), BigDecimal("7.4"), BigDecimal("61.3"), inconsistent = false)
        assertEquals(Texts.statsShareOfTrip("61,3 %") + Texts.SEPARATOR + "12,0 km", StatsFormatting.rankingRow(row))
        val clipped = row.copy(sharePct = BigDecimal("100"), inconsistent = true)
        assertTrue(Texts.STATS_CLIPPED_MARK in StatsFormatting.rankingRow(clipped))
    }

    @Test
    fun `the winter label spans the two years`() {
        assertEquals("2026", StatsFormatting.periodLabel(period(2026, null)))
        assertEquals(Texts.SEASON_WINTER + " 2025/26", StatsFormatting.periodLabel(period(2026, Season.WINTER)))
        assertEquals(Texts.SEASON_WINTER + " 2099/00", StatsFormatting.periodLabel(period(2100, Season.WINTER)))
        assertEquals(Texts.SEASON_AUTUMN + " 2025", StatsFormatting.periodLabel(period(2025, Season.AUTUMN)))
    }

    @Test
    fun `a period line lists the facts and says when there is no price`() {
        assertEquals(
            listOf(Texts.statsTrips(12), "450,0 km", "4,50 l/100 km", "312,40 zł", "69,42 zł/100 km", "EV 41,0 %")
                .joinToString(Texts.SEPARATOR),
            StatsFormatting.periodLine(period(2026, null)),
        )
        val noPrice = StatsFormatting.periodLine(period(2026, null, cost = null, ev = null))
        assertTrue(Texts.NO_PRICE in noPrice, noPrice)
        assertTrue("EV" !in noPrice, noPrice)
    }

    @Test
    fun `the comparable window starts on the first of January`() {
        assertEquals("1.01–31.10", StatsFormatting.window(LocalDate.of(2026, 10, 31)))
        assertEquals("1.01–5.03", StatsFormatting.window(LocalDate.of(2026, 3, 5)))
    }

    @Test
    fun `the text does not depend on the default locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ROOT, Locale.US, Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("ar"))) {
                Locale.setDefault(locale)
                assertEquals("61,3 %", StatsFormatting.share(BigDecimal("61.3")), locale.toString())
                assertEquals("1.01–31.10", StatsFormatting.window(LocalDate.of(2026, 10, 31)), locale.toString())
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `computeStats builds every section from the trips`() {
        val view = computeStats(
            listOf(
                // 20 January falls inside the window of 1 January - 1 February of both years.
                stored("a", "2025-01-20T10:00:00Z", km = 10.0, ev = 6.0),
                stored("b", "2026-02-01T10:00:00Z", km = 20.0, ev = 5.0, cost = null),
            ),
            WARSAW,
        )
        assertEquals(listOf(2025, 2026), view.years.map { it.year })
        assertEquals(listOf("a", "b"), view.ranking.rows.map { it.tripId })
        assertNotNull(view.yearOverYear)
        assertEquals(LocalDate.of(2026, 2, 1), view.windowEnd)
        assertEquals(Instant.parse("2025-01-20T10:00:00Z"), view.starts["a"])
        assertEquals(1, view.years[1].tripsWithoutCost)
    }

    @Test
    fun `no year over year without trips in the year before`() {
        val view = computeStats(listOf(stored("a", "2026-02-01T10:00:00Z")), WARSAW)
        assertNull(view.yearOverYear)
        assertNull(view.windowEnd)
    }

    @Test
    fun `the controller reads once per navigation and drops a late result after logout`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var reads = 0
        val gate = CompletableDeferred<Unit>()
        val controller = StatsController(
            read = {
                reads++
                gate.await()
                listOf(stored("a", "2026-02-01T10:00:00Z"))
            },
            zone = { WARSAW },
            scope = CoroutineScope(SupervisorJob() + io),
            io = io,
        )
        controller.ensureLoaded()
        controller.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, reads)

        controller.reset()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(controller.state.value.loading, "a result of the old session is dropped")
        assertNull(controller.state.value.view)
    }

    @Test
    fun `a failing read is shown without its text`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val controller = StatsController(
            read = { throw IllegalStateException("disk full at /data/secret") },
            zone = { WARSAW },
            scope = CoroutineScope(SupervisorJob() + io),
            io = io,
        )
        controller.load()
        advanceUntilIdle()
        assertTrue(controller.state.value.loadFailed)
        assertNull(controller.state.value.view)
    }
}
