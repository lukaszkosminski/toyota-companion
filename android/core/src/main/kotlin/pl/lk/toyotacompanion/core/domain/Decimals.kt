package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private const val PRICE_SCALE = 3
private const val MONEY_SCALE = 2
private const val LITER_SCALE = 3

/** Mirrors Python's default decimal.Context precision (28 significant digits). */
internal val DIVIDE_CONTEXT = MathContext(28, RoundingMode.HALF_UP)

/** Price per liter rounded to 0.001 zl/l, ROUND_HALF_UP. */
fun roundPrice(value: BigDecimal): BigDecimal = value.setScale(PRICE_SCALE, RoundingMode.HALF_UP)

/** Money amount rounded to 0.01 zl, ROUND_HALF_UP. */
fun roundMoney(value: BigDecimal): BigDecimal = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP)

/** Liters rounded to 0.001 l, ROUND_HALF_UP (pump precision). */
fun roundLiters(value: BigDecimal): BigDecimal = value.setScale(LITER_SCALE, RoundingMode.HALF_UP)

/** Lossless Double -> BigDecimal via shortest round-trip repr (like Python Decimal(str(x))). */
fun toBigDecimal(value: Double): BigDecimal = BigDecimal(value.toString())

internal fun BigDecimal.divideLikePythonDecimal(divisor: BigDecimal): BigDecimal =
    this.divide(divisor, DIVIDE_CONTEXT)
