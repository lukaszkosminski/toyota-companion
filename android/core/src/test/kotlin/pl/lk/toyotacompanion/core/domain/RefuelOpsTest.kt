@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.domain

import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertContains
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
            assertContains(first.id, cur.ts.toString())
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
