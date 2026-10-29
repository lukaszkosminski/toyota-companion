@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun bd(value: String): BigDecimal = BigDecimal(value)

private fun assertBd(expected: String, actual: BigDecimal?, label: String = "value") {
    val e = bd(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

private fun trip(
    id: String = "t1",
    start: Instant = Instant.parse("2026-10-05T09:00:00Z"),
    fuel: String = "0.60",
): Trip = Trip(
    id = id,
    start = start,
    end = Instant.parse("2026-10-05T09:30:00Z"),
    distanceKm = 14.6,
    fuelL = bd(fuel),
    evDistanceKm = null,
)

private fun refuel(
    id: String = "r1",
    ts: Instant = Instant.parse("2026-10-01T12:00:00Z"),
    price: String? = "6.19",
    status: RefuelStatus = RefuelStatus.CONFIRMED,
    levelBefore: Int = 20,
    levelAfter: Int = 80,
    litersSource: LitersSource? = LitersSource.RECEIPT,
): Refuel = Refuel(
    id = id,
    ts = ts,
    odometerKm = 1000.0,
    levelBefore = levelBefore,
    levelAfter = levelAfter,
    liters = if (price != null) bd("25.800") else null,
    pricePerLiter = price?.let(::bd),
    totalCost = null,
    status = status,
    litersSource = if (price != null) litersSource else null,
)

class CostOpsTest {

    @Nested
    inner class SpecExample {
        @Test
        fun `control_example_14_6_km_0_60_l_6_19_pln`() {
            // Trasa 14,6 km, fuel_l=0.60, cena 6,19 zl/l -> koszt 3,71 zl.
            val result = computeTripCost(trip(fuel = "0.60"), listOf(refuel(price = "6.19")))
            assertEquals("t1", result.tripId)
            assertBd("3.71", result.costPln, "cost_pln")
            assertBd("6.19", result.pricePerLiter, "price_per_liter")
            assertEquals("r1", result.refuelId)
            assertEquals(CostSource.REFUEL, result.source)
        }
    }

    @Nested
    inner class RefuelSelection {
        @Test
        fun `ignores_pending`() {
            val result = computeTripCost(
                trip(),
                listOf(refuel(status = RefuelStatus.PENDING)),
                defaultPrice = bd("6.00"),
            )
            assertEquals(CostSource.DEFAULT, result.source)
            assertNull(result.refuelId)
        }

        @Test
        fun `ignores_dismissed`() {
            val result = computeTripCost(
                trip(),
                listOf(refuel(status = RefuelStatus.DISMISSED)),
                defaultPrice = bd("6.00"),
            )
            assertEquals(CostSource.DEFAULT, result.source)
        }

        @Test
        fun `ignores_confirmed_without_price`() {
            val result = computeTripCost(
                trip(),
                listOf(refuel(price = null)),
                defaultPrice = bd("6.00"),
            )
            assertEquals(CostSource.DEFAULT, result.source)
        }

        @Test
        fun `ignores_refuel_after_trip_start`() {
            val late = refuel(ts = Instant.parse("2026-10-05T09:00:01Z"))
            val result = computeTripCost(trip(), listOf(late), defaultPrice = bd("6.00"))
            assertEquals(CostSource.DEFAULT, result.source)
        }

        @Test
        fun `refuel_exactly_at_start_counts`() {
            val atStart = refuel(ts = Instant.parse("2026-10-05T09:00:00Z"))
            val result = computeTripCost(trip(), listOf(atStart))
            assertEquals(CostSource.REFUEL, result.source)
            assertEquals("r1", result.refuelId)
        }

        @Test
        fun `picks_latest_confirmed_before_start`() {
            val old = refuel(id = "old", ts = Instant.parse("2026-09-01T00:00:00Z"), price = "5.99")
            val new = refuel(id = "new", ts = Instant.parse("2026-10-04T00:00:00Z"), price = "6.19")
            val after = refuel(id = "after", ts = Instant.parse("2026-10-06T00:00:00Z"), price = "7.00")
            val result = computeTripCost(trip(), listOf(old, after, new))
            assertEquals("new", result.refuelId)
            // ADR-005: (8.6 * 5.99 + 25.8 * 6.19) / 34.4 = 6.140
            assertBd("6.140", result.pricePerLiter, "price_per_liter")
            assertBd("3.68", result.costPln, "cost_pln")
        }

        @Test
        fun `uses_weighted_average_across_refuels`() {
            val first = refuel(id = "first", ts = Instant.parse("2026-09-01T00:00:00Z"), price = "5.99")
            val second = refuel(id = "second", ts = Instant.parse("2026-10-04T00:00:00Z"), price = "6.19")
            val result = computeTripCost(trip(), listOf(first, second))
            assertEquals(CostSource.REFUEL, result.source)
            assertEquals("second", result.refuelId)
            assertBd("6.140", result.pricePerLiter, "price_per_liter")
            assertBd("3.68", result.costPln, "cost_pln")
        }

        @Test
        fun `estimate_liters_update_tank_price`() {
            val estimate = refuel(id = "estimate", litersSource = LitersSource.ESTIMATE)
            val result = computeTripCost(trip(), listOf(estimate), defaultPrice = bd("6.00"))
            assertEquals(CostSource.REFUEL, result.source)
            assertEquals("estimate", result.refuelId)
            assertBd("3.71", result.costPln, "cost_pln")
        }

        @Test
        fun `ignores_confirmed_refuel_without_liters`() {
            val noLiters = refuel(price = null)
            val result = computeTripCost(trip(), listOf(noLiters), defaultPrice = bd("6.00"))
            assertEquals(CostSource.DEFAULT, result.source)
        }

        @Test
        fun `price_only_refuel_updates_tank_price_with_sensor_liters`() {
            val priceOnly = Refuel(
                id = "price-only",
                ts = Instant.parse("2026-10-01T12:00:00Z"),
                odometerKm = 1000.0,
                levelBefore = 20,
                levelAfter = 80,
                liters = null,
                pricePerLiter = bd("6.19"),
                totalCost = null,
                status = RefuelStatus.CONFIRMED,
                litersSource = null,
            )
            val result = computeTripCost(trip(), listOf(priceOnly), defaultPrice = bd("6.00"))
            assertEquals(CostSource.REFUEL, result.source)
            assertEquals("price-only", result.refuelId)
            assertBd("6.190", result.pricePerLiter, "price_per_liter")
            assertBd("3.71", result.costPln, "cost_pln")
        }

        @Test
        fun `refuel_beats_default_price`() {
            val result = computeTripCost(
                trip(),
                listOf(refuel(price = "6.19")),
                defaultPrice = bd("10.00"),
            )
            assertEquals(CostSource.REFUEL, result.source)
            assertBd("3.71", result.costPln, "cost_pln")
        }
    }

    @Nested
    inner class DefaultAndNone {
        @Test
        fun `default_price_used`() {
            val result = computeTripCost(trip(fuel = "0.60"), emptyList(), defaultPrice = bd("6.19"))
            assertEquals(CostSource.DEFAULT, result.source)
            assertBd("3.71", result.costPln, "cost_pln")
            assertBd("6.19", result.pricePerLiter, "price_per_liter")
            assertNull(result.refuelId)
        }

        @Test
        fun `no_refuel_no_default`() {
            val result = computeTripCost(trip(), emptyList())
            assertEquals("t1", result.tripId)
            assertNull(result.costPln)
            assertNull(result.pricePerLiter)
            assertNull(result.refuelId)
            assertEquals(CostSource.NONE, result.source)
        }

        @Test
        fun `empty_iterable_statuses_only_none_source`() {
            val refuels = listOf(
                refuel(id = "a", status = RefuelStatus.PENDING),
                refuel(id = "b", status = RefuelStatus.DISMISSED),
                refuel(id = "c", price = null),
            )
            assertEquals(CostSource.NONE, computeTripCost(trip(), refuels).source)
        }
    }

    @Nested
    inner class Rounding {
        @Test
        fun `cost_rounded_half_up_to_001`() {
            // 0.60 * 6.19 = 3.714 -> 3.71
            assertBd(
                "3.71",
                computeTripCost(trip(fuel = "0.60"), listOf(refuel(price = "6.19"))).costPln,
                "cost_pln",
            )
            // 0.50 * 6.19 = 3.095 -> 3.10 (ROUND_HALF_UP)
            assertBd(
                "3.10",
                computeTripCost(trip(fuel = "0.50"), listOf(refuel(price = "6.19"))).costPln,
                "cost_pln",
            )
        }

        @Test
        fun `default_cost_rounded`() {
            val result = computeTripCost(trip(fuel = "0.50"), emptyList(), defaultPrice = bd("6.19"))
            assertBd("3.10", result.costPln, "cost_pln")
        }
    }

    @Nested
    inner class RecomputeCosts {
        @Test
        fun `returns_one_cost_per_trip_in_input_order`() {
            val trips = listOf(trip("a"), trip("b"), trip("c"))
            val costs = recomputeCosts(trips, listOf(refuel()))
            assertEquals(listOf("a", "b", "c"), costs.map { it.tripId })
            assertTrue(costs.all { it.source == CostSource.REFUEL }) { "all REFUEL" }
        }

        @Test
        fun `retroactive_recount_after_price_confirmed`() {
            val trips = listOf(trip("a"), trip("b"))
            val pending = listOf(refuel(id = "r1", price = null, status = RefuelStatus.PENDING))

            val before = recomputeCosts(trips, pending, defaultPrice = bd("6.00"))
            assertEquals(listOf(CostSource.DEFAULT, CostSource.DEFAULT), before.map { it.source })

            val confirmed = pending.map {
                it.copy(
                    liters = bd("25.800"),
                    pricePerLiter = bd("6.19"),
                    litersSource = LitersSource.RECEIPT,
                    status = RefuelStatus.CONFIRMED,
                )
            }
            val after = recomputeCosts(trips, confirmed, defaultPrice = bd("6.00"))
            assertEquals(listOf(CostSource.REFUEL, CostSource.REFUEL), after.map { it.source })
            for (c in after) assertBd("3.71", c.costPln, "cost_pln")
        }

        @Test
        fun `empty_trips`() {
            assertEquals(emptyList(), recomputeCosts(emptyList(), listOf(refuel())))
        }
    }

    @Test
    fun `float_fuel_from_pytoyoda_is_accepted`() {
        // The Toyota client returns fuel consumed as double; it must not crash the cost.
        val t = Trip(
            id = "t",
            start = Instant.parse("2026-01-02T00:00:00Z"),
            end = Instant.parse("2026-01-02T01:00:00Z"),
            distanceKm = 14.6,
            fuelL = toBigDecimal(0.60),
            evDistanceKm = null,
        )
        val r = Refuel(
            id = "r",
            ts = Instant.parse("2026-01-01T00:00:00Z"),
            odometerKm = 1.0,
            levelBefore = 10,
            levelAfter = 90,
            liters = bd("34.400"),
            pricePerLiter = bd("6.19"),
            totalCost = null,
            status = RefuelStatus.CONFIRMED,
            litersSource = LitersSource.RECEIPT,
            filledToFull = true,
        )
        assertBd("3.71", computeTripCost(t, listOf(r)).costPln, "cost_pln")
    }
}
