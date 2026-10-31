@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.domain

import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

private fun bd(value: String): BigDecimal = BigDecimal(value)

private fun assertBd(expected: String, actual: BigDecimal?, label: String = "value") {
    val e = bd(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

private val TS_PREV: Instant = Instant.parse("2026-10-01T08:00:00Z")
private val TS_CUR: Instant = Instant.parse("2026-10-01T12:00:00Z")

private fun reading(ts: Instant, level: Int, odo: Double): FuelReading =
    FuelReading(ts = ts, levelPct = level, odometerKm = odo)

private fun pendingRefuel(
    id: String = "refuel-x",
    ts: Instant = TS_CUR,
    odometerKm: Double = 12345.0,
    levelBefore: Int = 15,
    levelAfter: Int = 90,
    liters: BigDecimal? = null,
    pricePerLiter: BigDecimal? = null,
    totalCost: BigDecimal? = null,
    status: RefuelStatus = RefuelStatus.PENDING,
): Refuel = Refuel(
    id = id,
    ts = ts,
    odometerKm = odometerKm,
    levelBefore = levelBefore,
    levelAfter = levelAfter,
    liters = liters,
    pricePerLiter = pricePerLiter,
    totalCost = totalCost,
    status = status,
)

class RefuelOpsTest {

    @Nested
    inner class DetectRefuel {
        @Test
        fun `jump_equal_to_min_detected`() {
            val prev = reading(TS_PREV, 10, 100.0)
            val cur = reading(TS_CUR, 18, 140.0) // jump == 8 == default min
            val refuel = detectRefuel(prev, cur)
            assertNotNull(refuel)
            assertEquals(RefuelStatus.PENDING, refuel.status)
        }

        @Test
        fun `detected_refuel_fields`() {
            val prev = reading(TS_PREV, 15, 100.0)
            val cur = reading(TS_CUR, 90, 140.5)
            val refuel = requireNotNull(detectRefuel(prev, cur))
            assertEquals(cur.ts, refuel.ts)
            assertEquals(cur.odometerKm, refuel.odometerKm)
            assertEquals(15, refuel.levelBefore)
            assertEquals(90, refuel.levelAfter)
            assertNull(refuel.liters)
            assertNull(refuel.pricePerLiter)
            assertNull(refuel.totalCost)
            assertEquals(RefuelStatus.PENDING, refuel.status)
        }

        @Test
        fun `jump_below_min_returns_none`() {
            val prev = reading(TS_PREV, 10, 100.0)
            val cur = reading(TS_CUR, 17, 140.0) // jump 7 < 8
            assertNull(detectRefuel(prev, cur))
        }

        @Test
        fun `no_jump_returns_none`() {
            val prev = reading(TS_PREV, 60, 100.0)
            val cur = reading(TS_CUR, 55, 140.0) // level dropped (driving)
            assertNull(detectRefuel(prev, cur))
        }

        @Test
        fun `custom_min_jump_pct`() {
            val prev = reading(TS_PREV, 10, 100.0)
            val cur = reading(TS_CUR, 17, 140.0) // jump 7
            assertNotNull(detectRefuel(prev, cur, minJumpPct = 7))
            assertNull(detectRefuel(prev, cur, minJumpPct = 8))
        }

        @Test
        fun `id_is_deterministic_from_cur_ts`() {
            val prev = reading(TS_PREV, 10, 100.0)
            val cur = reading(TS_CUR, 90, 140.0)
            val first = requireNotNull(detectRefuel(prev, cur))
            val second = requireNotNull(detectRefuel(prev, cur))
            assertEquals(first.id, second.id)
            assertEquals("refuel-2026-10-01T12:00:00Z", first.id)
        }

        @Test
        fun `id_uses_canonical_utc_for_aware_timestamp`() {
            val prev = reading(TS_PREV, 10, 100.0)
            val cur = reading(Instant.parse("2026-10-01T14:00:00+02:00"), 90, 140.0)
            val refuel = requireNotNull(detectRefuel(prev, cur))
            assertEquals("refuel-2026-10-01T12:00:00Z", refuel.id)
        }

        @Test
        fun `id_keeps_millisecond_precision`() {
            val prev = reading(TS_PREV, 10, 100.0)
            val cur = reading(Instant.parse("2026-10-01T12:00:00.123Z"), 90, 140.0)
            val refuel = requireNotNull(detectRefuel(prev, cur))
            assertEquals("refuel-2026-10-01T12:00:00.123Z", refuel.id)
        }

        @Test
        fun `cur_before_prev_raises`() {
            val prev = reading(TS_CUR, 10, 100.0)
            val cur = reading(TS_PREV, 90, 140.0)
            assertFailsWith<IllegalArgumentException> { detectRefuel(prev, cur) }
        }

        @Test
        fun `cur_equal_prev_raises`() {
            val prev = reading(TS_PREV, 10, 100.0)
            val cur = reading(TS_PREV, 90, 140.0)
            assertFailsWith<IllegalArgumentException> { detectRefuel(prev, cur) }
        }

        @Test
        fun `timestamp_check_wins_over_small_jump`() {
            // even without a refuel jump, non-increasing timestamps are invalid
            val prev = reading(TS_CUR, 50, 100.0)
            val cur = reading(TS_PREV, 50, 90.0)
            assertFailsWith<IllegalArgumentException> { detectRefuel(prev, cur) }
        }
    }

    @Nested
    inner class ConfirmCombinations {
        @Test
        fun `price_only`() {
            val out = confirmRefuel(pendingRefuel(), pricePerLiter = bd("6.19"))
            assertEquals(RefuelStatus.CONFIRMED, out.status)
            assertBd("6.190", out.pricePerLiter, "price_per_liter")
            assertNull(out.liters)
            assertNull(out.totalCost)
            assertNull(out.litersSource)
        }

        @Test
        fun `price_and_liters_computes_total`() {
            val out = confirmRefuel(
                pendingRefuel(),
                pricePerLiter = bd("6.19"),
                liters = bd("0.600"),
            )
            assertEquals(RefuelStatus.CONFIRMED, out.status)
            // 6.19 * 0.60 = 3.714 -> 3.71 (ROUND_HALF_UP to 0.01)
            assertBd("3.71", out.totalCost, "total_cost")
            assertBd("6.190", out.pricePerLiter, "price_per_liter")
            assertBd("0.600", out.liters, "liters")
            assertEquals(LitersSource.RECEIPT, out.litersSource)
        }

        @Test
        fun `price_and_total_computes_liters`() {
            val out = confirmRefuel(
                pendingRefuel(),
                pricePerLiter = bd("6.19"),
                totalCost = bd("3.71"),
            )
            assertEquals(RefuelStatus.CONFIRMED, out.status)
            // 3.71 / 6.19 = 0.59935... -> 0.599 (ROUND_HALF_UP to 0.001)
            assertBd("0.599", out.liters, "liters")
            assertBd("3.71", out.totalCost, "total_cost")
            assertEquals(LitersSource.DERIVED, out.litersSource)
        }

        @Test
        fun `liters_and_total_computes_price`() {
            val out = confirmRefuel(
                pendingRefuel(),
                liters = bd("0.600"),
                totalCost = bd("3.71"),
            )
            assertEquals(RefuelStatus.CONFIRMED, out.status)
            // 3.71 / 0.60 = 6.1833... -> 6.183
            assertBd("6.183", out.pricePerLiter, "price_per_liter")
            assertEquals(LitersSource.RECEIPT, out.litersSource)
        }

        @Test
        fun `filled_to_full_is_preserved`() {
            val out = confirmRefuel(
                pendingRefuel(),
                pricePerLiter = bd("6.19"),
                liters = bd("32.250"),
                filledToFull = true,
            )
            assertEquals(true, out.filledToFull)
            assertEquals(LitersSource.RECEIPT, out.litersSource)
        }

        @Test
        fun `explicit_estimate_source`() {
            val out = confirmRefuel(
                pendingRefuel(),
                pricePerLiter = bd("6.19"),
                liters = bd("32.250"),
                litersSource = LitersSource.ESTIMATE,
            )
            assertEquals(LitersSource.ESTIMATE, out.litersSource)
        }

        @Test
        fun `liters_source_without_liters_raises`() {
            assertFailsWith<IllegalArgumentException> {
                confirmRefuel(
                    pendingRefuel(),
                    pricePerLiter = bd("6.19"),
                    litersSource = LitersSource.RECEIPT,
                )
            }
        }

        @Test
        fun `filled_to_full_without_liters_raises`() {
            assertFailsWith<IllegalArgumentException> {
                confirmRefuel(
                    pendingRefuel(),
                    pricePerLiter = bd("6.19"),
                    filledToFull = true,
                )
            }
        }

        @Test
        fun `price_only_is_rounded_to_0001`() {
            val out = confirmRefuel(pendingRefuel(), pricePerLiter = bd("6.1949"))
            assertBd("6.195", out.pricePerLiter, "price_per_liter")
        }

        @Test
        fun `price_rounding_half_up`() {
            // 2.469 / 2 = 1.2345 -> 1.235 (ROUND_HALF_UP at 0.001)
            val out = confirmRefuel(
                pendingRefuel(),
                liters = bd("2.000"),
                totalCost = bd("2.469"),
            )
            assertBd("1.235", out.pricePerLiter, "price_per_liter")
        }

        @Test
        fun `total_rounding_half_up`() {
            // 6.19 * 0.65 = 4.0235 -> 4.02 (HALF_UP only applies at the 0.005 boundary)
            val out = confirmRefuel(
                pendingRefuel(),
                pricePerLiter = bd("6.19"),
                liters = bd("0.650"),
            )
            assertBd("4.02", out.totalCost, "total_cost")
            // exact half: 2.245 * 1 = 2.245 -> 2.25
            val out2 = confirmRefuel(
                pendingRefuel(),
                pricePerLiter = bd("2.245"),
                liters = bd("1.000"),
            )
            assertBd("2.25", out2.totalCost, "total_cost")
        }
    }

    @Nested
    inner class ConfirmErrors {
        @Test
        fun `no_values_raises`() {
            assertFailsWith<IllegalArgumentException> { confirmRefuel(pendingRefuel()) }
        }

        @Test
        fun `liters_only_raises`() {
            assertFailsWith<IllegalArgumentException> {
                confirmRefuel(pendingRefuel(), liters = bd("0.6"))
            }
        }

        @Test
        fun `total_only_raises`() {
            assertFailsWith<IllegalArgumentException> {
                confirmRefuel(pendingRefuel(), totalCost = bd("3.71"))
            }
        }

        @Test
        fun `all_three_raises`() {
            assertFailsWith<IllegalArgumentException> {
                confirmRefuel(
                    pendingRefuel(),
                    pricePerLiter = bd("6.19"),
                    liters = bd("0.6"),
                    totalCost = bd("3.71"),
                )
            }
        }

        @ParameterizedTest(name = "kwargs{index}")
        @CsvSource(
            delimiter = ',',
            value = [
                "0,,",
                "-6.19,,",
                "6.19,0,",
                "6.19,-0.5,",
                "6.19,,0",
                "6.19,,-1",
                ",0.5,0",
                ",0,3.71",
                ",0.5,-3.71",
            ],
        )
        fun `non_positive_values_raise`(price: String?, liters: String?, total: String?) {
            assertFailsWith<IllegalArgumentException> {
                confirmRefuel(
                    pendingRefuel(),
                    pricePerLiter = price?.takeIf { it.isNotEmpty() }?.let(::bd),
                    liters = liters?.takeIf { it.isNotEmpty() }?.let(::bd),
                    totalCost = total?.takeIf { it.isNotEmpty() }?.let(::bd),
                )
            }
        }
    }

    @Nested
    inner class Immutability {
        @Test
        fun `original_object_unchanged`() {
            val original = pendingRefuel()
            confirmRefuel(original, pricePerLiter = bd("6.19"))
            assertEquals(RefuelStatus.PENDING, original.status)
            assertNull(original.pricePerLiter)
            assertNull(original.liters)
            assertNull(original.totalCost)
        }

        @Test
        fun `returns_new_object`() {
            val original = pendingRefuel()
            val out = confirmRefuel(original, pricePerLiter = bd("6.19"))
            assertTrue(out !== original) { "confirmRefuel must return a new object" }
            assertEquals(original.id, out.id)
            assertEquals(original.ts, out.ts)
            assertEquals(original.levelBefore, out.levelBefore)
            assertEquals(original.levelAfter, out.levelAfter)
        }

        @Test
        fun `all_properties_are_final`() {
            val fields = Refuel::class.java.declaredFields.filter {
                !it.isSynthetic && !Modifier.isStatic(it.modifiers)
            }
            assertTrue(fields.isNotEmpty()) { "Refuel must have instance fields" }
            assertTrue(fields.all { Modifier.isFinal(it.modifiers) }) {
                "all Refuel properties must be val (final)"
            }
        }
    }

    @Nested
    inner class Estimate {
        @Test
        fun `estimate_from_level_jump`() {
            assertBd("25.800", estimateRefuelLiters(pendingRefuel(levelBefore = 20, levelAfter = 80)), "liters")
            assertBd("14.190", estimateRefuelLiters(pendingRefuel(levelBefore = 30, levelAfter = 63)), "liters")
        }

        @Test
        fun `estimate_none_without_jump`() {
            assertNull(estimateRefuelLiters(pendingRefuel(levelBefore = 80, levelAfter = 80)))
            assertNull(estimateRefuelLiters(pendingRefuel(levelBefore = 80, levelAfter = 70)))
        }

        @Test
        fun `estimate_none_for_levels_out_of_range`() {
            assertNull(estimateRefuelLiters(pendingRefuel(levelBefore = -1, levelAfter = 90)))
            assertNull(estimateRefuelLiters(pendingRefuel(levelBefore = 10, levelAfter = 0)))
            assertNull(estimateRefuelLiters(pendingRefuel(levelBefore = 10, levelAfter = 101)))
        }

        @Test
        fun `estimate_uses_capacity`() {
            val refuel = pendingRefuel(levelBefore = 20, levelAfter = 80)
            assertBd("30.000", estimateRefuelLiters(refuel, capacityL = bd("50")), "liters")
        }

        @Test
        fun `estimate_none_when_rounding_to_zero`() {
            // 1% of a 0.01 l tank is 0.0001 l: nothing to offer the user.
            val refuel = pendingRefuel(levelBefore = 50, levelAfter = 51)
            assertNull(estimateRefuelLiters(refuel, capacityL = bd("0.01")))
        }

        @Test
        fun `estimate_rounds_half_up`() {
            val refuel = pendingRefuel(levelBefore = 0, levelAfter = 1)
            assertBd("0.003", estimateRefuelLiters(refuel, capacityL = bd("0.25")), "liters")
        }

        @Test
        fun `estimate_rejects_non_positive_capacity`() {
            val refuel = pendingRefuel(levelBefore = 20, levelAfter = 80)
            assertFailsWith<IllegalArgumentException> { estimateRefuelLiters(refuel, capacityL = bd("0")) }
            assertFailsWith<IllegalArgumentException> { estimateRefuelLiters(refuel, capacityL = bd("-1")) }
        }

        @Test
        fun `accepted_estimate_matches_implicit_tank_price`() {
            // Accepting the estimate may not move the tank price (ADR-005).
            val refuel = pendingRefuel(levelBefore = 30, levelAfter = 63)
            val priceOnly = confirmRefuel(refuel, pricePerLiter = bd("6.50"))
            val accepted = confirmRefuel(
                refuel,
                pricePerLiter = bd("6.50"),
                liters = estimateRefuelLiters(refuel),
                litersSource = LitersSource.ESTIMATE,
            )
            val implicit = requireNotNull(tankPriceAfterRefuel(priceOnly, bd("6.00"))) { "implicit price is null" }
            val afterAccepting = requireNotNull(tankPriceAfterRefuel(accepted, bd("6.00"))) {
                "price after accepting the estimate is null"
            }
            assertTrue(implicit.compareTo(afterAccepting) == 0) { "tank price moved: $implicit vs $afterAccepting" }
            assertEquals(LitersSource.ESTIMATE, accepted.litersSource)
            assertBd("92.24", accepted.totalCost, "total_cost")
        }
    }

    @Nested
    inner class TankPriceAfterRefuel {
        private fun confirmed(
            ts: Instant = TS_CUR,
            levelBefore: Int = 20,
            levelAfter: Int = 80,
            price: String = "6.19",
            liters: String = "25.800",
        ): Refuel = confirmRefuel(
            pendingRefuel(ts = ts, levelBefore = levelBefore, levelAfter = levelAfter),
            pricePerLiter = bd(price),
            liters = bd(liters),
        )

        @Test
        fun `first_refuel_uses_own_price`() {
            assertBd("6.190", tankPriceAfterRefuel(confirmed(price = "6.19")), "tank_price")
        }

        @Test
        fun `weighted_average`() {
            val first = confirmed(price = "5.99")
            val second = confirmed(price = "6.19")
            // (8.6 * 5.99 + 25.8 * 6.19) / 34.4 = 6.140
            assertBd("5.990", tankPriceAfterRefuel(first), "tank_price")
            assertBd("6.140", tankPriceAfterRefuel(second, bd("5.990")), "tank_price")
        }

        @Test
        fun `estimate_liters_are_used`() {
            val refuel = confirmRefuel(
                pendingRefuel(levelBefore = 20, levelAfter = 80),
                pricePerLiter = bd("6.19"),
                liters = bd("25.800"),
                litersSource = LitersSource.ESTIMATE,
            )
            assertBd("6.140", tankPriceAfterRefuel(refuel, bd("5.990")), "tank_price")
        }

        @Test
        fun `price_only_uses_sensor_liters`() {
            // Only the price is entered; added = (80% - 20%) * 43 l = 25.8 l.
            val refuel = confirmRefuel(
                pendingRefuel(levelBefore = 20, levelAfter = 80),
                pricePerLiter = bd("6.19"),
            )
            assertBd("6.140", tankPriceAfterRefuel(refuel, bd("5.990")), "tank_price")
        }

        @Test
        fun `price_only_without_level_jump_is_ignored`() {
            val refuel = confirmRefuel(
                pendingRefuel(levelBefore = 50, levelAfter = 50),
                pricePerLiter = bd("6.19"),
            )
            assertNull(tankPriceAfterRefuel(refuel, bd("5.990")))
        }

        @Test
        fun `weighted_average_stays_between_prices`() {
            // Receipt (30 l) disagrees with the sensor jump (25.8 l): the average is
            // over the fuel that mixed, (8.6 * 5.99 + 30 * 6.19) / 38.6 = 6.145.
            val result = tankPriceAfterRefuel(confirmed(price = "6.19", liters = "30"), bd("5.990"))
            assertBd("6.145", result, "tank_price")
            val r = requireNotNull(result)
            assertTrue(r >= bd("5.99") && r <= bd("6.19"))
        }

        @Test
        fun `invalid_level_after_is_ignored`() {
            assertNull(tankPriceAfterRefuel(confirmed(levelAfter = 0)))
        }

        @Test
        fun `invalid_level_before_is_ignored`() {
            assertNull(tankPriceAfterRefuel(confirmed(levelBefore = 101)))
        }

        @Test
        fun `non_confirmed_is_ignored`() {
            assertNull(tankPriceAfterRefuel(pendingRefuel()))
        }

        @Test
        fun `zero_previous_price_falls_back_to_own_price`() {
            assertBd("6.190", tankPriceAfterRefuel(confirmed(), BigDecimal.ZERO), "tank_price")
        }

        @Test
        fun `empty_tank_before_refuel_uses_own_price`() {
            assertBd(
                "6.190",
                tankPriceAfterRefuel(confirmed(levelBefore = 0, price = "6.19"), bd("5.990")),
                "tank_price",
            )
        }

        @Test
        fun `capacity_must_be_positive`() {
            assertFailsWith<IllegalArgumentException> {
                tankPriceAfterRefuel(confirmed(), capacityL = BigDecimal.ZERO)
            }
        }

        @Test
        fun `default_capacity_is_43_liters`() {
            assertBd("43", DEFAULT_TANK_CAPACITY_L, "capacity")
        }
    }

    @Nested
    inner class DismissRefuel {
        @Test
        fun `sets_dismissed`() {
            val out = dismissRefuel(pendingRefuel())
            assertEquals(RefuelStatus.DISMISSED, out.status)
        }

        @Test
        fun `other_fields_preserved`() {
            val original = pendingRefuel(pricePerLiter = bd("6.190"))
            val out = dismissRefuel(original)
            assertTrue(out !== original) { "dismissRefuel must return a new object" }
            assertEquals(original.id, out.id)
            assertBd("6.190", out.pricePerLiter, "price_per_liter")
        }

        @Test
        fun `original_unchanged`() {
            val original = pendingRefuel()
            dismissRefuel(original)
            assertEquals(RefuelStatus.PENDING, original.status)
        }
    }

    @Test
    fun `confirm_value_rounding_to_zero_raises_value_error`() {
        val r = pendingRefuel()
        assertFailsWith<IllegalArgumentException> {
            confirmRefuel(r, liters = bd("0.0004"), totalCost = bd("10"))
        }
    }
}
