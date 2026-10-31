@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertBd(expected: String, actual: BigDecimal?, label: String = "value") {
    val e = BigDecimal(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

/** Python DAY_1 = datetime(2026, 10, 1, 12, 0, 0). */
private val DAY_1: Instant = Instant.parse("2026-10-01T12:00:00Z")

/** Python `at(days)` = DAY_1 + timedelta(days=days). */
private fun at(days: Double): Instant = DAY_1.plusSeconds(Math.round(days * 86_400.0).toLong())

private fun refuel(
    id: String,
    day: Double,
    liters: String? = "30.000",
    full: Boolean? = true,
    status: RefuelStatus = RefuelStatus.CONFIRMED,
    source: LitersSource? = LitersSource.RECEIPT,
    odometerKm: Double = 1000.0,
): Refuel =
    Refuel(
        id = id,
        ts = at(day),
        odometerKm = odometerKm,
        levelBefore = 20,
        levelAfter = 90,
        liters = liters?.let { BigDecimal(it) },
        pricePerLiter = if (status == RefuelStatus.CONFIRMED) BigDecimal("6.190") else null,
        totalCost = null,
        status = status,
        litersSource = if (liters != null) source else null,
        filledToFull = if (liters != null) full else null,
    )

private fun trip(
    id: String,
    day: Double,
    km: Double = 200.0,
    fuel: String = "9.000",
    ev: Double? = null,
): Trip =
    Trip(
        id = id,
        start = at(day),
        end = at(day).plusSeconds(2L * 3600L),
        distanceKm = km,
        fuelL = BigDecimal(fuel),
        evDistanceKm = ev,
    )

private fun threeTrips(): List<Trip> = listOf(trip("t1", 1.0), trip("t2", 2.0), trip("t3", 3.0))

class EconomyOpsTest {

    @Nested
    inner class FuelEconomyIntervals {

        @Test
        fun `two_full_receipts_compare`() {
            val refuels =
                listOf(refuel("a", 0.0, odometerKm = 1000.0), refuel("b", 10.0, odometerKm = 1600.0))
            val intervals = fuelEconomyIntervals(refuels, threeTrips())
            assertEquals(2, intervals.size)
            val interval = intervals[0]
            val tail = intervals[1]
            assertEquals(EconomyStatus.OK, interval.status)
            assertEquals("a", interval.startRefuelId)
            assertEquals("b", interval.endRefuelId)
            assertEquals(3, interval.trips)
            assertBd("600.0", interval.distanceKm, "distance_km")
            assertBd("27.000", interval.computerLiters, "computer_liters")
            assertBd("30.000", interval.actualLiters, "actual_liters")
            assertBd("4.50", interval.computerLPer100Km, "computer_l_per_100km")
            assertBd("5.00", interval.actualLPer100Km, "actual_l_per_100km")
            assertBd("11.1", interval.differencePct, "difference_pct")
            assertBd("600.0", interval.odometerKm, "odometer_km")
            assertEquals(EconomyStatus.OPEN, tail.status)
            assertEquals("b", tail.startRefuelId)
            assertNull(tail.endRefuelId)
        }

        @Test
        fun `partial_receipt_inside_adds_liters`() {
            val refuels =
                listOf(
                    refuel("a", 0.0),
                    refuel("p", 5.0, liters = "10.000", full = false),
                    refuel("b", 10.0, liters = "20.000"),
                )
            val interval = fuelEconomyIntervals(refuels, threeTrips())[0]
            assertEquals(EconomyStatus.OK, interval.status)
            assertBd("30.000", interval.actualLiters, "actual_liters")
        }

        @Test
        fun `estimate_inside_is_missing_receipt`() {
            val refuels =
                listOf(
                    refuel("a", 0.0),
                    refuel("p", 5.0, liters = "10.000", full = false, source = LitersSource.ESTIMATE),
                    refuel("b", 10.0),
                )
            val interval = fuelEconomyIntervals(refuels, threeTrips())[0]
            assertEquals(EconomyStatus.MISSING_RECEIPT, interval.status)
            assertNull(interval.actualLiters)
            assertNull(interval.actualLPer100Km)
            assertNull(interval.differencePct)
            assertBd("4.50", interval.computerLPer100Km, "computer_l_per_100km")
        }

        @Test
        fun `price_only_inside_is_missing_receipt`() {
            val refuels =
                listOf(refuel("a", 0.0), refuel("p", 5.0, liters = null), refuel("b", 10.0))
            assertEquals(
                EconomyStatus.MISSING_RECEIPT,
                fuelEconomyIntervals(refuels, threeTrips())[0].status,
            )
        }

        @Test
        fun `derived_liters_count`() {
            val refuels = listOf(refuel("a", 0.0), refuel("b", 10.0, source = LitersSource.DERIVED))
            assertEquals(EconomyStatus.OK, fuelEconomyIntervals(refuels, threeTrips())[0].status)
        }

        @Test
        fun `estimate_closing_refuel_is_missing_receipt`() {
            val refuels = listOf(refuel("a", 0.0), refuel("b", 10.0, source = LitersSource.ESTIMATE))
            assertEquals(
                EconomyStatus.MISSING_RECEIPT,
                fuelEconomyIntervals(refuels, threeTrips())[0].status,
            )
        }

        @Test
        fun `opening_refuel_liters_do_not_count`() {
            val refuels =
                listOf(
                    refuel("a", 0.0, liters = "99.000", source = LitersSource.ESTIMATE),
                    refuel("b", 10.0),
                )
            val interval = fuelEconomyIntervals(refuels, threeTrips())[0]
            assertEquals(EconomyStatus.OK, interval.status)
            assertBd("30.000", interval.actualLiters, "actual_liters")
        }

        @Test
        fun `pending_inside_blocks`() {
            val refuels =
                listOf(
                    refuel("a", 0.0),
                    refuel("p", 5.0, liters = null, status = RefuelStatus.PENDING),
                    refuel("b", 10.0),
                )
            val interval = fuelEconomyIntervals(refuels, threeTrips())[0]
            assertEquals(EconomyStatus.PENDING_REFUEL, interval.status)
            assertNull(interval.actualLiters)
        }

        @Test
        fun `dismissed_inside_blocks`() {
            val refuels =
                listOf(
                    refuel("a", 0.0),
                    refuel("p", 5.0, liters = null, status = RefuelStatus.DISMISSED),
                    refuel("b", 10.0),
                )
            assertEquals(
                EconomyStatus.DISMISSED_REFUEL,
                fuelEconomyIntervals(refuels, threeTrips())[0].status,
            )
        }

        @Test
        fun `pending_neither_opens_nor_closes`() {
            val refuels =
                listOf(
                    refuel("p0", -1.0, liters = null, status = RefuelStatus.PENDING),
                    refuel("a", 0.0),
                    refuel("p1", 5.0, liters = null, status = RefuelStatus.PENDING),
                )
            val intervals = fuelEconomyIntervals(refuels, threeTrips())
            assertEquals(1, intervals.size)
            val only = intervals[0]
            assertEquals(EconomyStatus.OPEN, only.status)
            assertEquals("a", only.startRefuelId)
        }

        @Test
        fun `refuels_before_the_first_full_open_nothing`() {
            val refuels = listOf(refuel("x", -5.0, full = false), refuel("a", 0.0))
            val intervals = fuelEconomyIntervals(refuels, threeTrips())
            assertEquals(listOf("a"), intervals.map { it.startRefuelId })
        }

        @Test
        fun `no_full_refuel_gives_nothing`() {
            assertTrue(
                fuelEconomyIntervals(listOf(refuel("x", 0.0, full = false)), threeTrips()).isEmpty(),
            )
        }

        @Test
        fun `open_interval_counts_trips_so_far`() {
            val intervals = fuelEconomyIntervals(listOf(refuel("a", 0.0)), threeTrips())
            assertEquals(1, intervals.size)
            val only = intervals[0]
            assertEquals(EconomyStatus.OPEN, only.status)
            assertEquals(3, only.trips)
            assertBd("600.0", only.distanceKm, "distance_km")
            assertBd("4.50", only.computerLPer100Km, "computer_l_per_100km")
            assertNull(only.actualLPer100Km)
        }

        @Test
        fun `trip_on_the_boundaries`() {
            val refuels = listOf(refuel("a", 0.0), refuel("b", 10.0))
            val trips = listOf(trip("at_start", 0.0, km = 300.0), trip("at_end", 10.0, km = 50.0))
            val intervals = fuelEconomyIntervals(refuels, trips)
            assertEquals(2, intervals.size)
            val interval = intervals[0]
            val tail = intervals[1]
            assertEquals(1, interval.trips)
            assertBd("300.0", interval.distanceKm, "distance_km")
            assertEquals(1, tail.trips)
            assertBd("50.0", tail.distanceKm, "tail distance_km")
        }

        @Test
        fun `trip_crossing_the_end_belongs_where_it_started`() {
            val refuels = listOf(refuel("a", 0.0), refuel("b", 10.0))
            val crossing =
                Trip(
                    id = "long",
                    start = at(9.99),
                    end = at(10.5),
                    distanceKm = 150.0,
                    fuelL = BigDecimal("7.000"),
                    evDistanceKm = null,
                )
            val interval = fuelEconomyIntervals(refuels, threeTrips() + crossing)[0]
            assertEquals(4, interval.trips)
            assertBd("750.0", interval.distanceKm, "distance_km")
        }

        @Test
        fun `no_trips_status`() {
            val interval = fuelEconomyIntervals(listOf(refuel("a", 0.0), refuel("b", 10.0)), emptyList())[0]
            assertEquals(EconomyStatus.NO_TRIPS, interval.status)
            assertNull(interval.computerLPer100Km)
            assertBd("30.000", interval.actualLiters, "actual_liters")
        }

        @Test
        fun `too_short_status`() {
            val refuels = listOf(refuel("a", 0.0), refuel("b", 10.0, liters = "4.000"))
            val interval =
                fuelEconomyIntervals(refuels, listOf(trip("t1", 1.0, km = 80.0, fuel = "3.600")))[0]
            assertEquals(EconomyStatus.TOO_SHORT, interval.status)
            assertBd("4.000", interval.actualLiters, "actual_liters")
            assertNull(interval.actualLPer100Km)
            assertBd("4.50", interval.computerLPer100Km, "computer_l_per_100km")
        }

        @Test
        fun `threshold_is_inclusive_at_the_minimum`() {
            val refuels = listOf(refuel("a", 0.0), refuel("b", 10.0, liters = "5.000"))
            val interval =
                fuelEconomyIntervals(refuels, listOf(trip("t1", 1.0, km = 100.0, fuel = "4.500")))[0]
            assertEquals(EconomyStatus.OK, interval.status)
        }

        @Test
        fun `odometer_not_positive_is_none`() {
            val refuels =
                listOf(refuel("a", 0.0, odometerKm = 1600.0), refuel("b", 10.0, odometerKm = 1600.0))
            assertNull(fuelEconomyIntervals(refuels, threeTrips())[0].odometerKm)
        }

        @Test
        fun `order_by_time_then_id`() {
            val refuels = listOf(refuel("b", 10.0), refuel("a", 0.0), refuel("c", 20.0))
            val intervals = fuelEconomyIntervals(refuels, threeTrips())
            assertEquals(
                listOf("a" to "b", "b" to "c", "c" to null),
                intervals.map { it.startRefuelId to it.endRefuelId },
            )
        }

        @Test
        fun `ev_distance_is_summed`() {
            val refuels = listOf(refuel("a", 0.0), refuel("b", 10.0))
            val trips =
                listOf(trip("t1", 1.0, ev = 50.25), trip("t2", 2.0, ev = null), trip("t3", 3.0, ev = 20.0))
            assertBd(
                "70.3",
                fuelEconomyIntervals(refuels, trips)[0].evDistanceKm,
                "ev_distance_km",
            )
        }

        @Test
        fun `rounding_half_up`() {
            val refuels = listOf(refuel("a", 0.0), refuel("b", 10.0, liters = "4.600"))
            val interval =
                fuelEconomyIntervals(refuels, listOf(trip("t1", 1.0, km = 100.0, fuel = "4.505")))[0]
            assertBd("4.51", interval.computerLPer100Km, "computer_l_per_100km")
            assertBd("4.60", interval.actualLPer100Km, "actual_l_per_100km")
            assertBd("2.1", interval.differencePct, "difference_pct")
        }

        @Test
        fun `negative_difference_when_the_computer_overstates`() {
            val refuels = listOf(refuel("a", 0.0), refuel("b", 10.0, liters = "24.000"))
            assertBd(
                "-11.1",
                fuelEconomyIntervals(refuels, threeTrips())[0].differencePct,
                "difference_pct",
            )
        }
    }

    @Nested
    inner class EconomyRounding {

        @Test
        fun `round_consumption_half_up`() {
            assertBd("4.51", roundConsumption(BigDecimal("4.505")), "round_consumption")
        }

        @Test
        fun `round_percent_half_up`() {
            assertBd("2.1", roundPercent(BigDecimal("2.05")), "round_percent")
            assertBd("-2.1", roundPercent(BigDecimal("-2.05")), "negative round_percent")
        }

        @Test
        fun `round_distance_half_up`() {
            assertBd("70.3", roundDistance(BigDecimal("70.25")), "round_distance")
        }
    }
}
