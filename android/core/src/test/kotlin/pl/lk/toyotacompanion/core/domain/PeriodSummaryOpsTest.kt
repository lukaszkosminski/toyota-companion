package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The period summary (PRD F54, docs/SPEC.md §54); the same test names live in `backend/tests/test_domain_period_summary.py`. */
class PeriodSummaryOpsTest {
    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")

    private fun d(value: String) = BigDecimal(value)

    private fun date(value: String) = LocalDate.parse(value)

    private fun trip(id: String, start: String, km: String, fuel: String, ev: Double? = null): Trip {
        val begin = Instant.parse(start)
        return Trip(id, begin, begin.plusSeconds(900), km.toDouble(), d(fuel), ev)
    }

    @Test
    fun `the_week_is_the_last_completed_monday_to_sunday`() {
        // Wednesday 2026-11-11: the week before the Monday 2026-11-09 is 2026-11-02 … 2026-11-08.
        val w = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-11"))
        assertEquals(date("2026-11-02"), w.from)
        assertEquals(date("2026-11-09"), w.until)
        assertEquals("week:2026-11-02", w.key)
    }

    @Test
    fun `on_a_monday_the_week_that_just_ended_is_the_one_before_it`() {
        assertEquals(date("2026-11-02"), lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-09")).from)
        // Sunday 2026-11-08 still belongs to the week that has not ended.
        assertEquals(date("2026-10-26"), lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-08")).from)
    }

    @Test
    fun `the_week_key_is_its_monday_so_the_year_boundary_needs_no_iso_week`() {
        // 2026-12-28 is a Monday; the week 2026-12-28 … 2027-01-03 ends on Sunday 2027-01-03 (ISO week 53 of 2026, then week 1 of 2027).
        val w = lastCompletedWindow(SummaryPeriod.WEEK, date("2027-01-04"))
        assertEquals("week:2026-12-28", w.key)
        assertEquals(date("2027-01-04"), w.until)
        assertEquals("week:2026-12-21", lastCompletedWindow(SummaryPeriod.WEEK, date("2027-01-03")).key)
    }

    @Test
    fun `the_month_is_the_previous_calendar_month_and_crosses_the_year`() {
        val w = lastCompletedWindow(SummaryPeriod.MONTH, date("2026-01-05"))
        assertEquals(date("2025-12-01"), w.from)
        assertEquals(date("2026-01-01"), w.until)
        assertEquals("month:2025-12", w.key)
        assertEquals("month:2026-10", lastCompletedWindow(SummaryPeriod.MONTH, date("2026-11-01")).key)
    }

    @Test
    fun `the_previous_window_has_the_same_length`() {
        val week = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-11"))
        assertEquals(date("2026-10-26"), previousWindow(week).from)
        assertEquals(date("2026-11-02"), previousWindow(week).until)
        // In March the last completed month is February; the one before it is January.
        val february = lastCompletedWindow(SummaryPeriod.MONTH, date("2026-03-02"))
        assertEquals(date("2026-02-01"), february.from)
        val january = previousWindow(february)
        assertEquals(date("2026-01-01"), january.from)
        assertEquals(date("2026-02-01"), january.until)
    }

    @Test
    fun `a_trip_belongs_to_the_local_day_of_its_start_in_the_zone_of_the_phone`() {
        // 2026-11-01 23:30 UTC is 2026-11-02 00:30 in Warsaw: it is the first trip of the week 2026-11-02 … 08.
        val w = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-11"))
        val totals = periodTotals(listOf(trip("a", "2026-11-01T23:30:00Z", "10", "0.6")), emptyList(), emptyList(), w, warsaw)
        assertEquals(1, totals.trips)
        val utc = periodTotals(listOf(trip("a", "2026-11-01T23:30:00Z", "10", "0.6")), emptyList(), emptyList(), w, ZoneId.of("UTC"))
        assertEquals(0, utc.trips)
    }

    @Test
    fun `the_day_of_the_time_change_is_counted_once_in_its_week`() {
        // 2026-10-25 is the Sunday the clocks go back in Warsaw: 23:30 UTC on the 25th is 00:30 on the Monday, so that trip is the next week.
        val sundayWeek = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-10-26"))
        val mondayWeek = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-02"))
        assertEquals(date("2026-10-26"), mondayWeek.from)
        val trips = listOf(
            trip("sun", "2026-10-25T00:30:00Z", "10", "0.6"),
            trip("mon", "2026-10-25T23:30:00Z", "10", "0.6"),
        )
        assertEquals(1, periodTotals(trips, emptyList(), emptyList(), sundayWeek, warsaw).trips)
        assertEquals(1, periodTotals(trips, emptyList(), emptyList(), mondayWeek, warsaw).trips)
    }

    @Test
    fun `the_totals_sum_km_and_litres_and_the_rate_is_the_sum_over_the_sum`() {
        val w = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-11"))
        val trips = listOf(
            trip("a", "2026-11-03T08:00:00Z", "1", "0.1"),
            trip("b", "2026-11-04T08:00:00Z", "1", "0.1"),
            trip("c", "2026-11-05T08:00:00Z", "8", "0.6"),
        )
        val t = periodTotals(trips, emptyList(), emptyList(), w, warsaw)
        assertEquals(d("10.0"), t.distanceKm)
        assertEquals(d("0.8"), t.fuelL)
        assertEquals(d("8.00"), t.lPer100Km)
    }

    @Test
    fun `zero_litre_trips_count_and_broken_trips_do_not`() {
        val w = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-11"))
        val trips = listOf(
            trip("electric", "2026-11-03T08:00:00Z", "10", "0"),
            trip("zero", "2026-11-04T08:00:00Z", "0", "0.5"),
            trip("negative", "2026-11-05T08:00:00Z", "5", "-0.1"),
        )
        val t = periodTotals(trips, emptyList(), emptyList(), w, warsaw)
        assertEquals(1, t.trips)
        assertEquals(d("0.00"), t.lPer100Km)
    }

    @Test
    fun `the_fuel_cost_is_null_without_a_price_and_the_expenses_are_added`() {
        val w = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-11"))
        val trips = listOf(trip("a", "2026-11-03T08:00:00Z", "10", "0.6"))
        val unpriced = periodTotals(trips, emptyList(), listOf(date("2026-11-04") to d("17.10")), w, warsaw)
        assertNull(unpriced.fuelCostPln)
        assertEquals(d("17.10"), unpriced.costPln)
        val priced = periodTotals(trips, listOf(TripCost("a", d("3.70"), d("6.20"), "r", CostSource.REFUEL)), listOf(date("2026-11-04") to d("17.10")), w, warsaw)
        assertEquals(d("3.70"), priced.fuelCostPln)
        assertEquals(d("20.80"), priced.costPln)
    }

    @Test
    fun `the_electric_share_is_taken_from_the_window`() {
        val w = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-11"))
        val trips = listOf(trip("a", "2026-11-03T08:00:00Z", "10", "0.6", ev = 4.0), trip("b", "2026-11-04T08:00:00Z", "10", "0.6"))
        // Share over the trips that have electric data: 4 km of 10 km is 40 %; the trip without data is not in the share.
        assertEquals(d("40.0"), periodTotals(trips, emptyList(), emptyList(), w, warsaw).evSharePct)
        assertNull(periodTotals(emptyList(), emptyList(), listOf(date("2026-11-04") to d("5")), w, warsaw).evSharePct)
    }

    @Test
    fun `a_period_with_nothing_is_empty_and_one_with_an_expense_is_not`() {
        val w = lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-11"))
        assertTrue(periodTotals(emptyList(), emptyList(), emptyList(), w, warsaw).isEmpty)
        assertTrue(!periodTotals(emptyList(), emptyList(), listOf(date("2026-11-04") to d("5")), w, warsaw).isEmpty)
    }

    @Test
    fun `the_percent_change_needs_a_positive_base`() {
        assertEquals(d("12"), percentChangeOf(d("112"), d("100")))
        assertEquals(d("-5"), percentChangeOf(d("95"), d("100")))
        assertNull(percentChangeOf(d("10"), d("0")))
        assertNull(percentChangeOf(null, d("100")))
    }

    @Test
    fun `off_is_nothing_and_a_first_run_is_a_baseline`() {
        assertEquals(SummaryDecision.Nothing, summaryDecision(null, null, date("2026-11-11"), 10))
        assertEquals(SummaryDecision.Baseline("week:2026-11-02"), summaryDecision(SummaryPeriod.WEEK, null, date("2026-11-11"), 10))
    }

    @Test
    fun `the_same_period_is_sent_once`() {
        assertEquals(SummaryDecision.Nothing, summaryDecision(SummaryPeriod.WEEK, "week:2026-11-02", date("2026-11-11"), 10))
    }

    @Test
    fun `quiet_hours_wait_and_the_daytime_hours_post_the_last_completed_period`() {
        val wait7 = summaryDecision(SummaryPeriod.WEEK, "week:2026-10-26", date("2026-11-11"), 7)
        assertEquals(SummaryDecision.Wait, wait7)
        assertEquals(SummaryDecision.Wait, summaryDecision(SummaryPeriod.WEEK, "week:2026-10-26", date("2026-11-11"), 22))
        val post8 = summaryDecision(SummaryPeriod.WEEK, "week:2026-10-26", date("2026-11-11"), 8)
        assertEquals(SummaryDecision.Post(lastCompletedWindow(SummaryPeriod.WEEK, date("2026-11-11"))), post8)
        assertTrue(summaryDecision(SummaryPeriod.WEEK, "week:2026-10-26", date("2026-11-11"), 21) is SummaryDecision.Post)
    }

    @Test
    fun `only_the_last_completed_period_is_sent_not_the_missed_ones`() {
        // Last report 2026-10-12; the phone was off for weeks: the decision names 2026-11-02 only.
        val decision = summaryDecision(SummaryPeriod.WEEK, "week:2026-10-12", date("2026-11-11"), 12)
        assertEquals("week:2026-11-02", (decision as SummaryDecision.Post).window.key)
    }
}
