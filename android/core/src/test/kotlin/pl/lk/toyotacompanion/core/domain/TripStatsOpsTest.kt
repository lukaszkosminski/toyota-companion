@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertBd(expected: String, actual: BigDecimal?, label: String = "value") {
    val e = BigDecimal(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/** Python `utc(year, month, day, hour=10, minute=0)`. */
private fun utc(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Instant =
    Instant.parse("%04d-%02d-%02dT%02d:%02d:00Z".format(year, month, day, hour, minute))

private fun trip(
    id: String,
    km: Double = 10.0,
    ev: Double? = 4.0,
    fuel: String = "0.500",
    start: Instant = utc(2026, 10, 5),
): Trip = Trip(id, start, start.plusSeconds(1_800), km, BigDecimal(fuel), ev)

private fun cost(id: String, pln: String? = "3.10", source: CostSource = CostSource.REFUEL): TripCost =
    TripCost(id, pln?.let { BigDecimal(it) }, pln?.let { BigDecimal("6.190") }, null, source)

/** Mirrors backend/tests/test_domain_tripstats.py - the same names without the `test_` prefix. */
class TripStatsOpsTest {

    @Nested
    inner class ElectricShare {
        @Test
        fun `share_of_trip_with_ev_distance`() {
            val share = electricShare(trip("t", km = 12.0, ev = 7.35))
            assertBd("61.3", share.sharePct)
            assertBd("7.4", share.evDistanceKm)
            assertBd("12.0", share.distanceKm)
            assertFalse(share.inconsistent)
        }

        @Test
        fun `share_of_trip_without_hdc_is_none`() {
            val share = electricShare(trip("t", ev = null))
            assertNull(share.sharePct)
            assertNull(share.evDistanceKm)
        }

        @Test
        fun `share_never_exceeds_one_hundred`() {
            val share = electricShare(trip("t", km = 5.0, ev = 5.04))
            assertBd("100", share.sharePct)
            assertTrue(share.inconsistent)
        }

        @Test
        fun `share_of_zero_distance_is_none`() {
            assertNull(electricShare(trip("t", km = 0.0, ev = 0.0)).sharePct)
        }

        @Test
        fun `aggregate_ignores_trips_without_ev`() {
            val trips = listOf(trip("a", km = 10.0, ev = 6.0), trip("b", km = 30.0, ev = null), trip("c", km = 10.0, ev = 2.0))
            assertBd("40.0", aggregateElectricShare(trips))
        }

        @Test
        fun `aggregate_none_when_no_ev_data`() {
            assertNull(aggregateElectricShare(listOf(trip("a", ev = null))))
        }
    }

    @Nested
    inner class ElectricRankingTests {
        @Test
        fun `ranking_orders_by_share_desc`() {
            val trips = listOf(trip("low", ev = 1.0), trip("high", ev = 9.0), trip("mid", ev = 5.0))
            assertEquals(listOf("high", "mid", "low"), electricTripRanking(trips).rows.map { it.tripId })
        }

        @Test
        fun `ranking_skips_short_trips`() {
            val ranking = electricTripRanking(listOf(trip("short", km = 0.9, ev = 0.9), trip("ok", km = 2.0, ev = 1.0)))
            assertEquals(listOf("ok"), ranking.rows.map { it.tripId })
            assertEquals(1, ranking.tooShort)
        }

        @Test
        fun `ranking_breaks_tie_by_distance_then_id`() {
            val trips = listOf(trip("b", km = 10.0, ev = 5.0), trip("long", km = 20.0, ev = 10.0), trip("a", km = 10.0, ev = 5.0))
            assertEquals(listOf("long", "a", "b"), electricTripRanking(trips).rows.map { it.tripId })
        }

        @Test
        fun `ranking_limit`() {
            val trips = (0 until 25).map { index -> trip("t%02d".format(index), ev = (index % 10).toDouble()) }
            assertEquals(10, electricTripRanking(trips, limit = 10).rows.size)
        }

        @Test
        fun `ranking_counts_reported_separately`() {
            val ranking = electricTripRanking(listOf(trip("none", ev = null), trip("short", km = 0.5, ev = 0.1), trip("ok")))
            assertEquals(Triple(1, 1, 1), Triple(ranking.withoutData, ranking.tooShort, ranking.rows.size))
        }
    }

    @Nested
    inner class PeriodStatsTests {
        @Test
        fun `period_stats_year_sums_distance_and_cost`() {
            val trips = listOf(
                trip("a", km = 100.0, ev = 40.0, fuel = "4.000", start = utc(2025, 6, 1)),
                trip("b", km = 50.0, ev = 10.0, fuel = "3.000", start = utc(2025, 9, 1)),
                trip("c", km = 20.0, ev = null, fuel = "1.000", start = utc(2026, 2, 1)),
            )
            val costs = listOf(cost("a", "24.76"), cost("b", "18.57"), cost("c", null, CostSource.NONE))
            val (y2025, y2026) = periodStats(trips, costs, WARSAW, PeriodKind.YEAR)
            assertEquals(Triple(2025, null, 2), Triple(y2025.year, y2025.season, y2025.trips))
            assertBd("150.0", y2025.distanceKm)
            assertBd("7.000", y2025.fuelL)
            assertBd("4.67", y2025.lPer100Km)
            assertBd("43.33", y2025.costPln)
            assertBd("28.89", y2025.costPer100Km)
            assertBd("33.3", y2025.evSharePct)
            assertBd("75.0", y2025.averageTripKm)
            assertEquals(1, y2026.tripsWithoutCost)
        }

        @Test
        fun `period_stats_cost_none_without_prices`() {
            val only = periodStats(listOf(trip("a")), listOf(cost("a", null, CostSource.NONE)), WARSAW, PeriodKind.YEAR).single()
            assertNull(only.costPln)
            assertNull(only.costPer100Km)
            assertEquals(1, only.tripsWithoutCost)
        }

        @Test
        fun `period_stats_price_per_km_none_when_cost_none`() {
            val only = periodStats(listOf(trip("a")), emptyList(), WARSAW, PeriodKind.YEAR).single()
            assertNull(only.costPer100Km)
            assertBd("5.00", only.lPer100Km)
        }

        @Test
        fun `default_price_counts_as_a_cost`() {
            val only = periodStats(listOf(trip("a")), listOf(cost("a", "3.10", CostSource.DEFAULT)), WARSAW, PeriodKind.YEAR).single()
            assertBd("3.10", only.costPln)
        }

        @Test
        fun `season_boundaries_by_local_date`() {
            assertEquals(
                listOf(Season.WINTER, Season.WINTER, Season.WINTER, Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.AUTUMN),
                listOf(12, 1, 2, 3, 6, 9, 11).map { seasonOf(it) },
            )
        }

        @Test
        fun `december_belongs_to_the_winter_of_the_next_year`() {
            val trips = listOf(trip("dec", start = utc(2025, 12, 15)), trip("jan", start = utc(2026, 1, 15)))
            val winter = periodStats(trips, emptyList(), WARSAW, PeriodKind.SEASON).single()
            assertEquals(Triple(2026, Season.WINTER, 2), Triple(winter.year, winter.season, winter.trips))
        }

        @Test
        fun `season_of_trip_at_midnight_in_other_zone`() {
            // 2026-02-28 23:30 UTC is already 1 March in Warsaw: spring, not winter.
            val spring = periodStats(listOf(trip("t", start = utc(2026, 2, 28, 23, 30))), emptyList(), WARSAW, PeriodKind.SEASON).single()
            assertEquals(Season.SPRING, spring.season)
        }

        @Test
        fun `periods_are_ordered_oldest_first`() {
            val trips = listOf(trip("s", start = utc(2026, 7, 1)), trip("w", start = utc(2026, 1, 1)), trip("a", start = utc(2025, 10, 1)))
            assertEquals(
                listOf(2025 to Season.AUTUMN, 2026 to Season.WINTER, 2026 to Season.SUMMER),
                periodStats(trips, emptyList(), WARSAW, PeriodKind.SEASON).map { it.year to it.season },
            )
        }
    }

    @Nested
    inner class YearOverYear {
        @Test
        fun `year_over_year_uses_comparable_window`() {
            val trips = listOf(
                trip("old_in", km = 30.0, start = utc(2025, 2, 10)),
                trip("old_out", km = 500.0, start = utc(2025, 11, 1)),
                trip("new", km = 40.0, start = utc(2026, 3, 1)),
            )
            val (current, previous) = assertNotNull(yearOverYear(trips, emptyList(), WARSAW))
            assertEquals(2026, current.year)
            assertBd("40.0", current.distanceKm)
            assertEquals(2025, previous.year)
            assertBd("30.0", previous.distanceKm)
        }

        @Test
        fun `year_over_year_none_when_no_overlap`() {
            assertNull(yearOverYear(listOf(trip("only", start = utc(2026, 3, 1))), emptyList(), WARSAW))
        }

        @Test
        fun `year_over_year_none_without_trips`() {
            assertNull(yearOverYear(emptyList(), emptyList(), WARSAW))
        }
    }
}
