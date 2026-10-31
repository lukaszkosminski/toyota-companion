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
    litersSource: LitersSource? = null,
    filledToFull: Boolean? = null,
): Refuel {
    val litersWasProvided = liters != null
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

    val litersSourceV = when {
        litersV == null -> {
            if (litersSource != null) throw IllegalArgumentException("litersSource requires liters")
            if (filledToFull != null) throw IllegalArgumentException("filledToFull requires liters")
            null
        }
        litersSource == null -> if (litersWasProvided) LitersSource.RECEIPT else LitersSource.DERIVED
        // Liters came from total/price, not from the receipt.
        litersSource == LitersSource.RECEIPT && !litersWasProvided -> LitersSource.DERIVED
        else -> litersSource
    }

    return refuel.copy(
        liters = litersV,
        pricePerLiter = priceV,
        totalCost = totalV,
        status = RefuelStatus.CONFIRMED,
        litersSource = litersSourceV,
        filledToFull = filledToFull ?: refuel.filledToFull,
    )
}

/** Return a copy of [refuel] with [RefuelStatus.DISMISSED]. */
fun dismissRefuel(refuel: Refuel): Refuel = refuel.copy(status = RefuelStatus.DISMISSED)

/**
 * Sensor estimate of the liters taken at [refuel] (PRD F2a, docs/SPEC.md §2a).
 *
 * `(levelAfter - levelBefore)% * capacityL` rounded to 0.001 l, HALF_UP — the
 * arithmetic §6 has always used internally when only the price was given. The
 * app offers this number to the user, who confirms it (`litersSource = ESTIMATE`)
 * or replaces it with the receipt.
 *
 * Returns null when the readings cannot support an estimate: a level outside its
 * range (`levelBefore` 0..100, `levelAfter` 1..100), no jump at all, or a jump so
 * small that it rounds to 0 l. Throws [IllegalArgumentException] for a
 * non-positive [capacityL], like [tankPriceAfterRefuel].
 *
 * Mirrors Python `estimate_refuel_liters`.
 */
fun estimateRefuelLiters(
    refuel: Refuel,
    capacityL: BigDecimal = DEFAULT_TANK_CAPACITY_L,
): BigDecimal? {
    require(capacityL > BigDecimal.ZERO) { "capacityL must be > 0 (got $capacityL)" }
    if (refuel.levelBefore !in 0..100 || refuel.levelAfter !in 1..100) return null
    val jump = refuel.levelAfter - refuel.levelBefore
    if (jump <= 0) return null
    val estimate = roundLiters(levelToLiters(jump, capacityL))
    return estimate.takeIf { it > BigDecimal.ZERO }
}

/**
 * Average price of fuel in the tank after a confirmed refuel (ADR-005).
 *
 * `litersBefore = levelBefore% * capacity`; `added` = [Refuel.liters]
 * (receipt, derived or sensor estimate) or, when only the price was given,
 * [estimateRefuelLiters]:
 *
 *     tankPrice = (litersBefore * previousTankPrice + added * price)
 *                 / (litersBefore + added)
 *
 * The denominator is the fuel that actually mixed, so the result always lies
 * between the previous price and the new price. Returns null when the refuel
 * is not confirmed, has no price, has invalid levels or no determinable added
 * liters. The first refuel (no previous tank price), an empty tank before the
 * refuel, or a non-positive previous price uses its own price. Rounded to
 * 0.001 zl/l, HALF_UP. Mirrors Python `tank_price_after_refuel`.
 */
fun tankPriceAfterRefuel(
    refuel: Refuel,
    previousTankPrice: BigDecimal? = null,
    capacityL: BigDecimal = DEFAULT_TANK_CAPACITY_L,
): BigDecimal? {
    require(capacityL > BigDecimal.ZERO) { "capacityL must be > 0 (got $capacityL)" }
    val price = refuel.pricePerLiter
    if (refuel.status != RefuelStatus.CONFIRMED ||
        price == null ||
        refuel.levelBefore !in 0..100 ||
        refuel.levelAfter !in 1..100
    ) {
        return null
    }

    val added = refuel.liters ?: estimateRefuelLiters(refuel, capacityL) ?: return null
    if (added <= BigDecimal.ZERO) return null

    val litersBefore = levelToLiters(refuel.levelBefore, capacityL)
    if (previousTankPrice == null ||
        previousTankPrice <= BigDecimal.ZERO ||
        litersBefore <= BigDecimal.ZERO
    ) {
        return roundPrice(price)
    }

    val weighted = litersBefore.multiply(previousTankPrice)
        .add(added.multiply(price))
        .divideLikePythonDecimal(litersBefore.add(added))
    return roundPrice(weighted)
}

private fun levelToLiters(levelPct: Int, capacityL: BigDecimal): BigDecimal =
    BigDecimal(levelPct)
        .divide(BigDecimal(100))
        .multiply(capacityL)

private fun setOfNotNull(vararg pairs: Pair<String, Any?>): Set<String> =
    pairs.filter { it.second != null }.map { it.first }.toSet()
