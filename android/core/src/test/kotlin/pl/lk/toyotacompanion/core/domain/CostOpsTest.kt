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
            // 20 % of 43 l = 8,6 l at the default 6,00 + 25,8 l at 6,19 -> 211,302 / 34,4 = 6,1425 -> 6,143; 0,60 l -> 3,6858 -> 3,69
            assertBd("6.143", result.pricePerLiter, "price_per_liter")
            assertBd("3.69", result.costPln, "cost_pln")
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
            // sensor liters 25,8 mixed with 8,6 l at the default 6,00: 6,143 as above; 0,60 l -> 3,6858 -> 3,69
            assertBd("6.143", result.pricePerLiter, "price_per_liter")
            assertBd("3.69", result.costPln, "cost_pln")
        }

        @Test
        fun `refuel_beats_default_price`() {
            val result = computeTripCost(
                trip(),
                listOf(refuel(price = "6.19")),
                defaultPrice = bd("10.00"),
            )
            assertEquals(CostSource.REFUEL, result.source)
            assertEquals("r1", result.refuelId)
            // the default is only the price of the 8,6 l already in the tank: (8,6 x 10,00 + 25,8 x 6,19) / 34,4 = 7,1425 -> 7,143; 0,60 l -> 4,2858 -> 4,29
            assertBd("7.143", result.pricePerLiter, "price_per_liter")
            assertBd("4.29", result.costPln, "cost_pln")
        }
    }

    /** ADR-005 amendment 2026-11-08: the default price is the price of the fuel already in the tank, before the first confirmed refuel only. */
    @Nested
    inner class DefaultPriceInTank {
        @Test
        fun `default_price_is_the_tank_price_before_the_first_refuel`() {
            // The owner's case: 67 % -> 100 % at 5,92 zl/l (price only, sensor liters 14,19 = 33 % of 43 l);
            // the 28,81 l already in the tank were priced at the default 6,30.
            val first = Refuel(
                id = "r-08-11",
                ts = Instant.parse("2026-11-08T10:42:00Z"),
                odometerKm = 12000.0,
                levelBefore = 67,
                levelAfter = 100,
                liters = null,
                pricePerLiter = bd("5.92"),
                totalCost = null,
                status = RefuelStatus.CONFIRMED,
                litersSource = null,
            )
            val result = computeTripCost(
                trip(start = Instant.parse("2026-11-08T17:21:00Z"), fuel = "0.77"),
                listOf(first),
                defaultPrice = bd("6.30"),
            )
            assertEquals(CostSource.REFUEL, result.source)
            assertEquals("r-08-11", result.refuelId)
            // (28,81 x 6,30 + 14,19 x 5,92) / 43 = 265,5078 / 43 = 6,1746 -> 6,175; 0,77 l x 6,175 = 4,75475 -> 4,75
            assertBd("6.175", result.pricePerLiter, "price_per_liter")
            assertBd("4.75", result.costPln, "cost_pln")
        }

        @Test
        fun `first_refuel_without_default_price_uses_its_own_price`() {
            val result = computeTripCost(trip(), listOf(refuel(price = "6.19")))
            assertBd("6.19", result.pricePerLiter, "price_per_liter")
        }

        @Test
        fun `first_refuel_on_an_empty_tank_ignores_the_default_price`() {
            // an empty tank (level_before 0) holds no old fuel, so nothing is mixed in
            val empty = refuel(price = "6.19", levelBefore = 0, levelAfter = 60)
            val result = computeTripCost(trip(), listOf(empty), defaultPrice = bd("10.00"))
            assertBd("6.19", result.pricePerLiter, "price_per_liter")
        }

        @Test
        fun `default_price_mixes_only_into_the_first_refuel`() {
            val first = refuel(id = "r1", ts = Instant.parse("2026-10-01T12:00:00Z"), price = "6.19")
            val second = refuel(id = "r2", ts = Instant.parse("2026-10-03T12:00:00Z"), price = "5.00")
            val result = computeTripCost(trip(), listOf(first, second), defaultPrice = bd("10.00"))
            // first: 7,143 (see refuel_beats_default_price); second: (8,6 x 7,143 + 25,8 x 5,00) / 34,4 = 5,5357 -> 5,536
            assertEquals("r2", result.refuelId)
            assertBd("5.536", result.pricePerLiter, "price_per_liter")
        }

        @Test
        fun `zero_default_price_is_no_tank_price`() {
            val result = computeTripCost(trip(), listOf(refuel(price = "6.19")), defaultPrice = bd("0"))
            assertBd("6.19", result.pricePerLiter, "price_per_liter")
        }

        @Test
        fun `zero_default_price_before_any_refuel_is_no_price`() {
            // zero or negative is no price: never a trip for 0,00 zl
            for (default in listOf("0", "-5")) {
                val result = computeTripCost(trip(), emptyList(), defaultPrice = bd(default))
                assertEquals(CostSource.NONE, result.source, default)
                assertNull(result.costPln, default)
            }
        }

        @Test
        fun `changing_the_default_price_changes_the_first_refuel`() {
            val trips = listOf(trip("a"))
            val first = listOf(refuel(price = "6.19"))
            val atTen = recomputeCosts(trips, first, defaultPrice = bd("10.00"))
            val atSix = recomputeCosts(trips, first, defaultPrice = bd("6.00"))
            val removed = recomputeCosts(trips, first)
            // 8,6 l at the default + 25,8 l at 6,19: (86 + 159,702) / 34,4 = 7,1425 -> 7,143; (51,6 + 159,702) / 34,4 = 6,1425 -> 6,143; no default: 6,19
            assertBd("7.143", atTen.single().pricePerLiter, "at ten")
            assertBd("6.143", atSix.single().pricePerLiter, "at six")
            assertBd("6.19", removed.single().pricePerLiter, "removed")
        }

        @Test
        fun `a_refuel_with_unusable_levels_leaves_the_default_price`() {
            // level_after 0 is not above level_before 20: the refuel gives no tank price, so the trip keeps the default
            val broken = refuel(price = "6.19", levelBefore = 20, levelAfter = 0)
            val result = computeTripCost(trip(), listOf(broken), defaultPrice = bd("6.30"))
            assertEquals(CostSource.DEFAULT, result.source)
            assertBd("6.30", result.pricePerLiter, "price_per_liter")
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
            // the refuel mixes with the default 6,00 for the 8,6 l in the tank: 6,143 x 0,60 l = 3,6858 -> 3,69
            for (c in after) assertBd("3.69", c.costPln, "cost_pln")
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

    @Nested
    inner class AfterANewRefuel {
        private val cap = bd("40")
        private val oldTrip = trip("old", Instant.parse("2026-10-01T08:00:00Z"), "10.0")
        private val midTrip = trip("mid", Instant.parse("2026-10-05T08:00:00Z"), "10.0")
        private val newTrip = trip("new", Instant.parse("2026-10-10T08:00:00Z"), "10.0")
        private val first = refuel("r1", Instant.parse("2026-10-02T12:00:00Z"), "6.00", levelBefore = 20, levelAfter = 80)

        // 25 % of 40 l = 10 l at 6.00 meet 25.8 l at 7.00: (60 + 180.6) / 35.8 = 6.721.
        private fun second(ts: String) = refuel("r2", Instant.parse(ts), "7.00", levelBefore = 25, levelAfter = 80)

        private fun costs(vararg refuels: Refuel) =
            recomputeCosts(listOf(oldTrip, midTrip, newTrip), refuels.toList(), null, cap).associateBy { it.tripId }

        @Test
        fun `a refuel after a trip leaves that trip and the ones before it on their price`() {
            val before = costs(first)
            val after = costs(first, second("2026-10-08T12:00:00Z"))
            assertEquals(before.getValue("old"), after.getValue("old"))
            assertEquals(before.getValue("mid"), after.getValue("mid"))
            assertBd("60.00", after.getValue("mid").costPln, "mid")
        }

        @Test
        fun `a trip before every refuel has no price without a default`() {
            val old = costs(first, second("2026-10-08T12:00:00Z")).getValue("old")
            assertNull(old.costPln)
            assertEquals(CostSource.NONE, old.source)
        }

        @Test
        fun `a trip after the new refuel gets the mixed tank price`() {
            val new = costs(first, second("2026-10-08T12:00:00Z")).getValue("new")
            assertBd("6.721", new.pricePerLiter, "price")
            assertBd("67.21", new.costPln, "cost")
            assertEquals("r2", new.refuelId)
        }

        @Test
        fun `a refuel dated before a trip changes that trip`() {
            val backdated = costs(first, second("2026-10-03T12:00:00Z"))
            assertBd("67.21", backdated.getValue("mid").costPln, "mid")
        }

        @Test
        fun `a refuel not yet confirmed changes nothing`() {
            val pending = second("2026-10-08T12:00:00Z").copy(status = RefuelStatus.PENDING)
            assertEquals(costs(first), costs(first, pending))
        }
    }
}
