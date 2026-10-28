package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal

private val ALLOWED_COMBOS: Set<Set<String>> = setOf(
    setOf("price_per_liter"),
    setOf("liters", "total_cost"),
    setOf("price_per_liter", "liters"),
    setOf("price_per_liter", "total_cost"),
)

/**
 * Detect a refuel from a jump in fuel level between two readings.
 *
 * Returns a [Refuel] with [RefuelStatus.PENDING] when
 * `cur.levelPct - prev.levelPct >= minJumpPct`, else null.
 * Throws [IllegalArgumentException] when `cur.ts <= prev.ts`.
 */
fun detectRefuel(prev: FuelReading, cur: FuelReading, minJumpPct: Int = 8): Refuel? {
    if (!cur.ts.isAfter(prev.ts)) {
        throw IllegalArgumentException(
            "cur.ts (${cur.ts}) must be strictly after prev.ts (${prev.ts})",
        )
    }
    if (cur.levelPct - prev.levelPct < minJumpPct) {
        return null
    }
    return Refuel(
        id = "refuel-" + cur.ts.toString(),
        ts = cur.ts,
        odometerKm = cur.odometerKm,
        levelBefore = prev.levelPct,
        levelAfter = cur.levelPct,
        liters = null,
        pricePerLiter = null,
        totalCost = null,
        status = RefuelStatus.PENDING,
    )
}

/**
 * Return a copy of [refuel] with [RefuelStatus.CONFIRMED] and finalized
 * money fields.
 *
 * Admissible combinations of provided values: (price), (liters + totalCost),
 * (price + liters), (price + totalCost). Any other combination throws
 * [IllegalArgumentException].
 *
 * The missing magnitude is derived (price = total/liters,
 * total = price*liters, liters = total/price), divisions use
 * MathContext(28, HALF_UP) to mirror Python's decimal context, then prices
 * are rounded to 0.001, amounts to 0.01, liters to 0.001 (ROUND_HALF_UP).
 * Any provided value <= 0 throws [IllegalArgumentException]. The input
 * object is never mutated.
 */
fun confirmRefuel(
    refuel: Refuel,
    pricePerLiter: BigDecimal? = null,
    liters: BigDecimal? = null,
    totalCost: BigDecimal? = null,
): Refuel {
    val given = setOfNotNull(
        "price_per_liter" to pricePerLiter,
        "liters" to liters,
        "total_cost" to totalCost,
    )
    if (given !in ALLOWED_COMBOS) {
        throw IllegalArgumentException(
            "invalid combination of pricePerLiter/liters/totalCost; " +
                "allowed: (price), (liters+total_cost), (price+liters), " +
                "(price+total_cost)",
        )
    }

    for ((name, value) in listOf(
        "price_per_liter" to pricePerLiter,
        "liters" to liters,
        "total_cost" to totalCost,
    )) {
        if (value != null && value <= BigDecimal.ZERO) {
            throw IllegalArgumentException("$name must be > 0 (got $value)")
        }
    }

    var priceV = pricePerLiter?.let(::roundPrice)
    var litersV = liters?.let(::roundLiters)
    var totalV = totalCost?.let(::roundMoney)

    for ((name, value) in listOf(
        "price_per_liter" to priceV,
        "liters" to litersV,
        "total_cost" to totalV,
    )) {
        if (value != null && value <= BigDecimal.ZERO) {
            throw IllegalArgumentException("$name rounds to 0; value too small")
        }
    }

    // The combination was validated above; branch on null-checks.
    if (priceV != null && litersV != null) {
        totalV = roundMoney(priceV.multiply(litersV))
    } else if (priceV != null && totalV != null) {
        litersV = roundLiters(totalV.divideLikePythonDecimal(priceV))
    } else if (priceV == null && litersV != null && totalV != null) {
        priceV = roundPrice(totalV.divideLikePythonDecimal(litersV))
    }
    // price-only: litersV and totalV stay unknown (null).

    return refuel.copy(
        liters = litersV,
        pricePerLiter = priceV,
        totalCost = totalV,
        status = RefuelStatus.CONFIRMED,
    )
}

/** Return a copy of [refuel] with [RefuelStatus.DISMISSED]. */
fun dismissRefuel(refuel: Refuel): Refuel = refuel.copy(status = RefuelStatus.DISMISSED)

private fun setOfNotNull(vararg pairs: Pair<String, Any?>): Set<String> =
    pairs.filter { it.second != null }.map { it.first }.toSet()
