@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested

private fun assertBd(expected: String, actual: BigDecimal?, label: String = "value") {
    val e = BigDecimal(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun utc(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Instant =
    Instant.parse("%04d-%02d-%02dT%02d:%02d:00Z".format(year, month, day, hour, minute))

private fun trip(
    id: String,
    km: Double = 10.0,
    ev: Double? = 4.0,
    fuel: String = "0.500",
    start: Instant = utc(2026, 10, 5),
): Trip = Trip(id, start, start.plusSeconds(1_800), km, BigDecimal(fuel), ev)

private fun byId(records: Records): Map<String, Record> = records.items.associateBy { it.recordId }

/** Mirrors backend/tests/test_domain_records.py - the same names without the `test_` prefix. */
class RecordsOpsTest {

    @Nested
    inner class Empty {
        @Test
        fun no_trips_returns_empty() {
            val records = computeRecords(emptyList(), WARSAW)
            assertTrue(records.empty)
            assertEquals(emptyList(), records.items)
        }
    }

    @Nested
    inner class LongestTrip {
        @Test
        fun longest_trip_picks_max_distance() {
            val trips = listOf(
                trip("a", km = 12.34, start = utc(2026, 10, 5)),
                trip("b", km = 312.4, start = utc(2026, 6, 12, 8)),
                trip("c", km = 45.0, start = utc(2026, 9, 1)),
            )
            val records = byId(computeRecords(trips, WARSAW))
            assertBd("312.4", records.getValue("longest_trip").value)
            assertEquals("km", records.getValue("longest_trip").unit)
            assertEquals("2026-06-12", records.getValue("longest_trip").date.toString())
        }

        @Test
        fun longest_trip_rounds_to_tenth() {
            val records = byId(computeRecords(listOf(trip("a", km = 99.96)), WARSAW))
            assertBd("100.0", records.getValue("longest_trip").value)
        }
    }

    @Nested
    inner class LongestEvTrip {
        @Test
        fun longest_ev_trip_uses_ev_distance() {
            val trips = listOf(
                trip("a", km = 50.0, ev = 12.34, start = utc(2026, 3, 1)),
                trip("b", km = 80.0, ev = 45.6, start = utc(2026, 7, 20)),
            )
            val records = byId(computeRecords(trips, WARSAW))
            assertBd("45.6", records.getValue("longest_ev_trip").value)
            assertEquals("2026-07-20", records.getValue("longest_ev_trip").date.toString())
        }

        @Test
        fun longest_ev_trip_omitted_without_ev_data() {
            val trips = listOf(trip("a", km = 50.0, ev = null), trip("b", km = 80.0, ev = null))
            val records = byId(computeRecords(trips, WARSAW))
            assertFalse(records.containsKey("longest_ev_trip"))
        }
    }

    @Nested
    inner class BestEvShare {
        @Test
        fun best_ev_share_requires_twenty_km() {
            val trips = listOf(
                trip("short", km = 19.9, ev = 19.9),
                trip("ok", km = 25.0, ev = 20.0),
            )
            val records = byId(computeRecords(trips, WARSAW))
            assertBd("80.0", records.getValue("best_ev_share").value)
            assertEquals("%", records.getValue("best_ev_share").unit)
            assertEquals("25.0 km", records.getValue("best_ev_share").context)
        }

        @Test
        fun best_ev_share_clips_at_hundred() {
            val records = byId(computeRecords(listOf(trip("a", km = 30.0, ev = 30.5)), WARSAW))
            assertBd("100.0", records.getValue("best_ev_share").value)
        }

        @Test
        fun best_ev_share_omitted_when_all_short() {
            val trips = listOf(trip("a", km = 5.0, ev = 4.0), trip("b", km = 10.0, ev = 9.0))
            val records = byId(computeRecords(trips, WARSAW))
            assertFalse(records.containsKey("best_ev_share"))
        }
    }

    @Nested
    inner class BestEconomyTrip {
        @Test
        fun best_economy_trip_requires_fifty_km() {
            val trips = listOf(
                trip("short", km = 10.0, fuel = "0.200", start = utc(2026, 1, 1)),
                trip("long", km = 60.0, fuel = "3.000", start = utc(2026, 5, 5)),
            )
            val records = byId(computeRecords(trips, WARSAW))
            assertBd("5.00", records.getValue("best_economy_trip").value)
            assertEquals("60.0 km", records.getValue("best_economy_trip").context)
        }

        @Test
        fun best_economy_trip_picks_minimum() {
            val trips = listOf(
                trip("a", km = 100.0, fuel = "6.00", start = utc(2026, 2, 1)),
                trip("b", km = 200.0, fuel = "8.00", start = utc(2026, 3, 1)),
            )
            val records = byId(computeRecords(trips, WARSAW))
            assertBd("4.00", records.getValue("best_economy_trip").value)
            assertEquals("2026-03-01", records.getValue("best_economy_trip").date.toString())
        }

        @Test
        fun best_economy_trip_omitted_when_all_short() {
            val records = byId(computeRecords(listOf(trip("a", km = 49.9, fuel = "2.0")), WARSAW))
            assertFalse(records.containsKey("best_economy_trip"))
        }
    }

    @Nested
    inner class MostKmMonth {
        @Test
        fun most_km_month_sums_by_local_month() {
            val trips = listOf(
                trip("a", km = 100.0, start = utc(2026, 1, 15, 10)),
                trip("b", km = 200.0, start = utc(2026, 1, 20, 9)),
                trip("c", km = 250.0, start = utc(2026, 2, 10, 9)),
            )
            val records = byId(computeRecords(trips, WARSAW))
            assertBd("300.0", records.getValue("most_km_month").value)
            assertEquals("2026-01-01", records.getValue("most_km_month").date.toString())
            assertEquals("styczeń 2026", records.getValue("most_km_month").context)
        }

        @Test
        fun most_km_month_uses_local_date_not_utc() {
            // 2025-12-31 23:30 Warsaw is 2025-12-31 22:30 UTC -> still December 2025.
            val trips = listOf(
                trip("a", km = 100.0, start = Instant.parse("2025-12-31T22:30:00Z")),
                trip("b", km = 50.0, start = utc(2026, 1, 15)),
            )
            val records = byId(computeRecords(trips, WARSAW))
            assertEquals("grudzień 2025", records.getValue("most_km_month").context)
            assertBd("100.0", records.getValue("most_km_month").value)
        }
    }

    @Nested
    inner class BestEconomyMonth {
        @Test
        fun best_economy_month_requires_500_km() {
            val trips = listOf(
                trip("a", km = 300.0, fuel = "18.0", start = utc(2026, 1, 10)),
                trip("b", km = 600.0, fuel = "30.0", start = utc(2026, 2, 10)),
            )
            val records = byId(computeRecords(trips, WARSAW))
            assertBd("5.00", records.getValue("best_economy_month").value)
            assertEquals("luty 2026", records.getValue("best_economy_month").context)
        }

        @Test
        fun best_economy_month_pooled_not_average() {
            val trips = listOf(
                trip("a1", km = 300.0, fuel = "15.0", start = utc(2026, 1, 5)),
                trip("a2", km = 300.0, fuel = "21.0", start = utc(2026, 1, 20)),
                trip("b1", km = 550.0, fuel = "30.25", start = utc(2026, 2, 5)),
            )
            val records = byId(computeRecords(trips, WARSAW))
            assertBd("5.50", records.getValue("best_economy_month").value)
            assertEquals("luty 2026", records.getValue("best_economy_month").context)
        }

        @Test
        fun best_economy_month_omitted_when_all_months_short() {
            val records = byId(computeRecords(listOf(trip("a", km = 100.0, fuel = "5.0", start = utc(2026, 1, 5))), WARSAW))
            assertFalse(records.containsKey("best_economy_month"))
        }
    }

    @Nested
    inner class AllRecordsTogether {
        @Test
        fun full_archive_yields_all_six_records() {
            val trips = listOf(
                trip("t1", km = 12.0, ev = 6.0, fuel = "0.6", start = utc(2026, 1, 10)),
                trip("t2", km = 312.4, ev = 45.6, fuel = "14.0", start = utc(2026, 6, 12, 8)),
                trip("t3", km = 600.0, ev = 100.0, fuel = "27.0", start = utc(2026, 6, 20, 9)),
            )
            val records = computeRecords(trips, WARSAW)
            assertFalse(records.empty)
            assertEquals(
                setOf(
                    "longest_trip", "longest_ev_trip", "best_ev_share",
                    "best_economy_trip", "most_km_month", "best_economy_month",
                ),
                records.items.map { it.recordId }.toSet(),
            )
        }

        @Test
        fun archive_of_only_short_trips_is_not_empty() {
            val records = computeRecords(listOf(trip("a", km = 3.0, ev = 1.0, fuel = "0.2", start = utc(2026, 1, 5))), WARSAW)
            assertFalse(records.empty)
            val ids = records.items.map { it.recordId }.toSet()
            assertTrue("longest_trip" in ids)
            assertTrue("most_km_month" in ids)
        }
    }
}
