package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private const val PRICE_SCALE = 3
private const val MONEY_SCALE = 2
private const val LITER_SCALE = 3
private const val CONSUMPTION_SCALE = 2
private const val PERCENT_SCALE = 1
private const val DISTANCE_SCALE = 1

/** Tank capacity of the owner's Corolla Hybrid 2025 (PRD §7, ADR-005). */
val DEFAULT_TANK_CAPACITY_L: BigDecimal = BigDecimal("43")

/** Mirrors Python's default decimal.Context precision (28 significant digits). */
internal val DIVIDE_CONTEXT = MathContext(28, RoundingMode.HALF_UP)

/** Price per liter rounded to 0.001 zl/l, ROUND_HALF_UP. */
fun roundPrice(value: BigDecimal): BigDecimal = value.setScale(PRICE_SCALE, RoundingMode.HALF_UP)

/** Money amount rounded to 0.01 zl, ROUND_HALF_UP. */
fun roundMoney(value: BigDecimal): BigDecimal = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP)

/** Liters rounded to 0.001 l, ROUND_HALF_UP (pump precision). */
fun roundLiters(value: BigDecimal): BigDecimal = value.setScale(LITER_SCALE, RoundingMode.HALF_UP)

/** Fuel economy rounded to 0.01 l/100 km, ROUND_HALF_UP. */
fun roundConsumption(value: BigDecimal): BigDecimal =
    value.setScale(CONSUMPTION_SCALE, RoundingMode.HALF_UP)

/** Percentage rounded to 0.1 %, ROUND_HALF_UP. */
fun roundPercent(value: BigDecimal): BigDecimal = value.setScale(PERCENT_SCALE, RoundingMode.HALF_UP)

/** Distance rounded to 0.1 km, ROUND_HALF_UP. */
fun roundDistance(value: BigDecimal): BigDecimal = value.setScale(DISTANCE_SCALE, RoundingMode.HALF_UP)

/** Lossless Double -> BigDecimal via shortest round-trip repr (like Python Decimal(str(x))). */
fun toBigDecimal(value: Double): BigDecimal = BigDecimal(value.toString())

internal fun BigDecimal.divideLikePythonDecimal(divisor: BigDecimal): BigDecimal =
    this.divide(divisor, DIVIDE_CONTEXT)
