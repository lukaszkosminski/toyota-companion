package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The best time to leave (PRD F53, docs/SPEC.md §53); the same test names live in `backend/tests/test_domain_besttime.py`. */
class BestTimeOpsTest {
    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")

    private fun trip(id: String, start: String, minutes: Long, km: String, fuel: String): Trip {
        val begin = Instant.parse(start)
        return Trip(id, begin, begin.plusSeconds(minutes * 60), km.toDouble(), BigDecimal(fuel), null)
    }

    private fun cell(grid: BestTimeGrid, day: DayKind, band: DepartureBand): BestTimeCell? =
        grid.cells.firstOrNull { it.day == day && it.band == band }

    @Test
    fun `the_departure_band_is_the_local_hour`() {
        assertEquals(DepartureBand.NIGHT, departureBandOf(4))
        assertEquals(DepartureBand.H5_7, departureBandOf(5))
        assertEquals(DepartureBand.H5_7, departureBandOf(6))
        assertEquals(DepartureBand.H7_9, departureBandOf(7))
        assertEquals(DepartureBand.H7_9, departureBandOf(8))
        assertEquals(DepartureBand.H9_12, departureBandOf(9))
        assertEquals(DepartureBand.H9_12, departureBandOf(11))
        assertEquals(DepartureBand.H12_15, departureBandOf(12))
        assertEquals(DepartureBand.H12_15, departureBandOf(14))
        assertEquals(DepartureBand.H15_18, departureBandOf(15))
        assertEquals(DepartureBand.H15_18, departureBandOf(17))
        assertEquals(DepartureBand.H18_21, departureBandOf(18))
        assertEquals(DepartureBand.H18_21, departureBandOf(20))
        assertEquals(DepartureBand.NIGHT, departureBandOf(21))
        assertEquals(DepartureBand.NIGHT, departureBandOf(23))
        assertEquals(DepartureBand.NIGHT, departureBandOf(0))
    }

    @Test
    fun `the_day_kind_is_working_from_monday_to_friday_and_weekend_otherwise`() {
        assertEquals(DayKind.WORKING, dayKindOf(DayOfWeek.MONDAY))
        assertEquals(DayKind.WORKING, dayKindOf(DayOfWeek.FRIDAY))
        assertEquals(DayKind.WEEKEND, dayKindOf(DayOfWeek.SATURDAY))
        assertEquals(DayKind.WEEKEND, dayKindOf(DayOfWeek.SUNDAY))
    }

    @Test
    fun `the_median_of_an_odd_count_is_the_middle_drive`() {
        assertEquals(20L, medianMinutes(listOf(600L, 1200L, 3600L)))
    }

    @Test
    fun `the_median_of_an_even_count_is_the_mean_of_the_two_middle_drives`() {
        // 10, 12, 14, 30 minutes: the middle two are 12 and 14, the median 13.
        assertEquals(13L, medianMinutes(listOf(600L, 720L, 840L, 1800L)))
    }

    @Test
    fun `the_median_rounds_half_up_to_a_whole_minute`() {
        // 15 and 16 minutes: the median is 15.5 minutes, which is 16 (half up).
        assertEquals(16L, medianMinutes(listOf(900L, 960L)))
    }

    @Test
    fun `a_cell_needs_three_trips`() {
        val trips = listOf(
            trip("a", "2026-11-10T06:30:00Z", 20, "10", "0.6"),
            trip("b", "2026-11-11T06:30:00Z", 20, "10", "0.6"),
        )
        assertEquals(0, bestTimeGrid(trips, warsaw).cells.size)
        val three = trips + trip("c", "2026-11-12T06:30:00Z", 20, "10", "0.6")
        assertEquals(1, bestTimeGrid(three, warsaw).cells.size)
    }

    @Test
    fun `litres_per_100km_is_the_sum_over_the_sum`() {
        // 06:30 UTC is 07:30 in Warsaw in November: the band 7–9.
        // 1 km at 0.1 l, 1 km at 0.1 l and 8 km at 0.6 l: 0.8 l on 10 km is 8.00, the mean of the rates would be 9.17.
        val trips = listOf(
            trip("a", "2026-11-10T06:30:00Z", 5, "1", "0.1"),
            trip("b", "2026-11-11T06:30:00Z", 5, "1", "0.1"),
            trip("c", "2026-11-12T06:30:00Z", 5, "8", "0.6"),
        )
        assertEquals(BigDecimal("8.00"), cell(bestTimeGrid(trips, warsaw), DayKind.WORKING, DepartureBand.H7_9)?.lPer100Km)
    }

    @Test
    fun `a_trip_on_the_battery_stays_in_the_cell`() {
        val trips = (0 until 3).map { trip("z$it", "2026-11-${"%02d".format(it + 9)}T06:30:00Z", 20, "10", "0") }
        val c = cell(bestTimeGrid(trips, warsaw), DayKind.WORKING, DepartureBand.H7_9)
        assertEquals(3, c?.trips)
        assertEquals(BigDecimal("0.00"), c?.lPer100Km)
    }

    @Test
    fun `the_hour_is_the_local_hour_so_a_summer_time_start_is_not_an_hour_early`() {
        // 2026-07-01 05:30 UTC is 07:30 in Warsaw (summer time): the band is 7–9, not 5–7.
        val trips = (0 until 3).map { trip("s$it", "2026-07-0${it + 1}T05:30:00Z", 20, "10", "0.6") }
        val grid = bestTimeGrid(trips, warsaw)
        assertNull(cell(grid, DayKind.WORKING, DepartureBand.H5_7))
        assertEquals(3, cell(grid, DayKind.WORKING, DepartureBand.H7_9)?.trips)
    }

    @Test
    fun `a_trip_across_midnight_belongs_to_the_band_of_its_start`() {
        // Friday 2026-07-03 19:50 UTC is 21:50 in Warsaw: the night band of a working day, though the drive ends after midnight.
        val trips = (0 until 3).map { trip("n$it", "2026-07-03T19:50:00Z", 40, "12", "0.8") }
        assertEquals(3, cell(bestTimeGrid(trips, warsaw), DayKind.WORKING, DepartureBand.NIGHT)?.trips)
    }

    @Test
    fun `a_weekend_start_is_its_own_day_kind`() {
        // Saturday 2026-07-04 07:00 UTC is 09:00 in Warsaw.
        val trips = (0 until 3).map { trip("w$it", "2026-07-04T07:00:00Z", 20, "10", "0.6") }
        assertEquals(3, cell(bestTimeGrid(trips, warsaw), DayKind.WEEKEND, DepartureBand.H9_12)?.trips)
    }

    @Test
    fun `broken_trips_are_not_counted`() {
        val trips = listOf(
            trip("a", "2026-11-10T06:30:00Z", 0, "10", "0.6"),
            trip("b", "2026-11-11T06:30:00Z", 20, "0", "0.6"),
            trip("c", "2026-11-12T06:30:00Z", 20, "10", "-0.1"),
            trip("d", "2026-11-13T06:30:00Z", 20, "10", "0.6"),
        )
        assertEquals(1, bestTimeGrid(trips, warsaw).trips)
    }

    @Test
    fun `the_fastest_and_the_slowest_need_two_cells_and_are_the_lowest_and_the_highest_median`() {
        val trips = (0 until 3).map { trip("m$it", "2026-11-${"%02d".format(it + 9)}T06:30:00Z", 24, "10", "0.6") } +
            (0 until 3).map { trip("n$it", "2026-11-${"%02d".format(it + 9)}T08:30:00Z", 17, "10", "0.6") }
        val grid = bestTimeGrid(trips, warsaw)
        assertEquals(DepartureBand.H9_12, grid.fastest?.band)
        assertEquals(17L, grid.fastest?.medianMinutes)
        assertEquals(DepartureBand.H7_9, grid.slowest?.band)
        assertEquals(24L, grid.slowest?.medianMinutes)
        val single = bestTimeGrid(trips.take(3), warsaw)
        assertNull(single.fastest)
        assertNull(single.slowest)
    }
}
