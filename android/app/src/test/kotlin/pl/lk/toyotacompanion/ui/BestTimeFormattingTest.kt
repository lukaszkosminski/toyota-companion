package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.BestTimeCell
import pl.lk.toyotacompanion.core.domain.BestTimeGrid
import pl.lk.toyotacompanion.core.domain.DayKind
import pl.lk.toyotacompanion.core.domain.DepartureBand
import pl.lk.toyotacompanion.core.domain.Trip

private fun cell(day: DayKind, band: DepartureBand, median: Long, trips: Int = 5, lPer100: String? = "6.00") =
    BestTimeCell(day, band, trips, median, lPer100?.let(::BigDecimal))

/** The best time to leave (PRD F53, docs/SPEC.md §53): the words, the numbers and the conclusion of one route. */
class BestTimeFormattingTest {
    private var previousLanguage = AppLanguage.PL

    @Before
    fun usePolish() {
        previousLanguage = Lang.current
        Lang.current = AppLanguage.PL
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }

    @Test
    fun `the cell name reads the day kind and the band`() {
        assertEquals("robocze 9–12", BestTimeFormatting.cellName(cell(DayKind.WORKING, DepartureBand.H9_12, 17)))
        assertEquals("weekend 21–5", BestTimeFormatting.cellName(cell(DayKind.WEEKEND, DepartureBand.NIGHT, 20)))
        Lang.current = AppLanguage.EN
        assertEquals("weekdays 9–12", BestTimeFormatting.cellName(cell(DayKind.WORKING, DepartureBand.H9_12, 17)))
    }

    @Test
    fun `the conclusion names the fastest and the slowest cell with their medians`() {
        val grid = BestTimeGrid(
            trips = 10,
            cells = listOf(cell(DayKind.WORKING, DepartureBand.H7_9, 24), cell(DayKind.WORKING, DepartureBand.H9_12, 17)),
        )
        assertEquals("Najszybciej: robocze 9–12, mediana 17 min; najwolniej: robocze 7–9, 24 min", BestTimeFormatting.conclusion(grid))
        Lang.current = AppLanguage.EN
        assertEquals("Fastest: weekdays 9–12, median 17 min; slowest: weekdays 7–9, 24 min", BestTimeFormatting.conclusion(grid))
    }

    @Test
    fun `less than two minutes apart is no clear difference`() {
        val grid = BestTimeGrid(
            trips = 10,
            cells = listOf(cell(DayKind.WORKING, DepartureBand.H7_9, 18), cell(DayKind.WORKING, DepartureBand.H9_12, 17)),
        )
        assertEquals("Czas podobny o każdej porze: różnica poniżej 2 min (mediana ok. 17 min).", BestTimeFormatting.conclusion(grid))
        val two = BestTimeGrid(
            trips = 10,
            cells = listOf(cell(DayKind.WORKING, DepartureBand.H7_9, 19), cell(DayKind.WORKING, DepartureBand.H9_12, 17)),
        )
        assertTrue(BestTimeFormatting.conclusion(two)!!.startsWith("Najszybciej"), "two minutes is a difference")
    }

    @Test
    fun `one cell gives no conclusion but a note, and no cell gives the other note`() {
        val one = BestTimeGrid(5, listOf(cell(DayKind.WORKING, DepartureBand.H7_9, 20)))
        assertNull(BestTimeFormatting.conclusion(one))
        assertEquals(Texts.BEST_TIME_ONE_CELL, BestTimeFormatting.shortNote(one))
        val none = BestTimeGrid(2, emptyList())
        assertNull(BestTimeFormatting.conclusion(none))
        assertEquals(Texts.BEST_TIME_NOT_ENOUGH, BestTimeFormatting.shortNote(none))
        assertNull(BestTimeFormatting.shortNote(BestTimeGrid(10, listOf(cell(DayKind.WORKING, DepartureBand.H7_9, 20), cell(DayKind.WORKING, DepartureBand.H9_12, 17)))))
    }

    @Test
    fun `the trips are declined in Polish and English`() {
        assertEquals("1 przejazd", Texts.bestTimeTrips(1))
        assertEquals("2 przejazdy", Texts.bestTimeTrips(2))
        assertEquals("4 przejazdy", Texts.bestTimeTrips(4))
        assertEquals("5 przejazdów", Texts.bestTimeTrips(5))
        assertEquals("12 przejazdów", Texts.bestTimeTrips(12))
        assertEquals("22 przejazdy", Texts.bestTimeTrips(22))
        assertEquals("1\u00A0024 przejazdy", Texts.bestTimeTrips(1024))
        Lang.current = AppLanguage.EN
        assertEquals("1 trip", Texts.bestTimeTrips(1))
        assertEquals("7 trips", Texts.bestTimeTrips(7))
    }

    @Test
    fun `the helper line has the trips and the litres per 100 km, and the route title the length`() {
        assertEquals("5 przejazdów · 6,00 l/100 km", BestTimeFormatting.helper(cell(DayKind.WORKING, DepartureBand.H7_9, 20)))
        assertEquals("5 przejazdów", BestTimeFormatting.helper(cell(DayKind.WORKING, DepartureBand.H7_9, 20, lPer100 = null)))
        assertEquals("Odcinek 2 · 9,4 km · 14 przejazdów", BestTimeFormatting.routeTitle(2, BigDecimal("9.4"), 14))
        assertEquals("17 min", BestTimeFormatting.medianValue(cell(DayKind.WORKING, DepartureBand.H9_12, 17)))
    }

    @Test
    fun `the texts differ between the languages and carry no vin`() {
        fun all() = listOf(
            Texts.BEST_TIME_TITLE, Texts.BEST_TIME_HINT, Texts.BEST_TIME_NOT_ENOUGH, Texts.BEST_TIME_ONE_CELL,
            Texts.bestTimeNoDifference("17"), Texts.bestTimeConclusion("robocze 9–12", "17", "robocze 7–9", "24"),
            Texts.bestTimeRouteTitle(1, "9,4 km", "5 przejazdów"),
        )
        val polish = all()
        Lang.current = AppLanguage.EN
        val english = all()
        for ((pl, en) in polish.zip(english)) {
            assertFalse("VIN" in pl + en, pl)
            assertFalse(pl == en, "untranslated: $pl")
        }
    }

    // ---- the route on the screen ----

    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")

    /** A route of the same two places (coordinates), driven at the given UTC start, so `sameRoutes` groups them. */
    private fun drive(id: String, start: String, minutes: Long, fuel: String = "0.6"): StoredTrip {
        val begin = Instant.parse(start)
        val trip = Trip(id, begin, begin.plusSeconds(minutes * 60), 10.0, BigDecimal(fuel), null)
        val record = TripRecord(trip, "VINAAA111", startLat = 54.37, startLon = 18.63, endLat = 54.40, endLon = 18.60)
        return StoredTrip(record, null)
    }

    @Test
    fun `a route with two cells of data gets its conclusion and its rows`() {
        // 2026-11-09 … 13 is Monday to Friday. 06:30 UTC is 07:30 in Warsaw (band 7–9), 08:30 UTC is 09:30 (band 9–12).
        val days = listOf("09", "10", "11", "12", "13")
        val trips = days.map { drive("slow$it", "2026-11-${it}T06:30:00Z", 20) } + days.map { drive("fast$it", "2026-11-${it}T08:30:00Z", 16) }
        val view = computeDrivingStyle(trips, zone)
        val route = view.bestTimes.single()
        assertEquals(1, route.number)
        assertEquals(10, route.trips)
        assertEquals(2, route.grid.cells.size)
        assertEquals(
            "Najszybciej: robocze 9–12, mediana 16 min; najwolniej: robocze 7–9, 20 min",
            BestTimeFormatting.conclusion(route.grid),
        )
        assertEquals(BigDecimal("6.00"), route.grid.cells.first().lPer100Km)
    }

    @Test
    fun `a route with fewer than five trips gets no best time`() {
        val trips = listOf("09", "10", "11", "12").map { drive("t$it", "2026-11-${it}T06:30:00Z", 20) }
        assertTrue(computeDrivingStyle(trips, zone).bestTimes.isEmpty())
    }

    @Test
    fun `the best time is taken from the routes on the screen, at most five`() {
        val trips = listOf("09", "10", "11", "12", "13").map { drive("t$it", "2026-11-${it}T06:30:00Z", 20) }
        assertEquals(1, computeDrivingStyle(trips, zone).bestTimes.size)
    }

    @Test
    fun `the bar of a row grows with the drive time`() {
        val fast = cell(DayKind.WORKING, DepartureBand.H5_7, median = 34)
        val slow = cell(DayKind.WORKING, DepartureBand.H7_9, median = 44)
        assertEquals(1f, BestTimeFormatting.fraction(slow, slowest = 44))
        assertTrue(BestTimeFormatting.fraction(fast, slowest = 44) < 1f)
        assertEquals(34f / 44f, BestTimeFormatting.fraction(fast, slowest = 44))
        assertEquals(0f, BestTimeFormatting.fraction(fast, slowest = 0))
    }
}
