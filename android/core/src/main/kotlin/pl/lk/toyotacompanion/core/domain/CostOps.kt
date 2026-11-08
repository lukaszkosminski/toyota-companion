package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal

/**
 * Cost of a trip based on the weighted tank price (ADR-005).
 *
 * Confirmed refuels up to `trip.start` are applied in chronological order; each
 * one with a price updates the average price in the tank (see
 * [tankPriceAfterRefuel]; liters may be a sensor estimate). The latest such price is used: cost is
 * `trip.fuelL * tankPrice` rounded to 0.01 zl (HALF_UP), [CostSource.REFUEL].
 * With no usable refuel but a [defaultPrice], [CostSource.DEFAULT]. Otherwise
 * everything is null and [CostSource.NONE]. Pending/dismissed refuels are ignored.
 *
 * A positive [defaultPrice] is also the price of the fuel already in the tank before the first confirmed refuel
 * (ADR-005 amendment 2026-11-08): the first refuel mixes with it like with any earlier refuel, instead of pricing the
 * whole tank at its own price. An empty tank (`levelBefore` 0) still gives the refuel's own price.
 */
fun computeTripCost(
    trip: Trip,
    refuels: Iterable<Refuel>,
    defaultPrice: BigDecimal? = null,
    capacityL: BigDecimal = DEFAULT_TANK_CAPACITY_L,
): TripCost {
    // Zero or negative is no price, never a 0,00 zl trip (docs/SPEC.md §4, §12.2).
    val default = defaultPrice?.takeIf { it.signum() > 0 }
    var tankPrice: BigDecimal? = default
    var usedRefuel: Refuel? = null
    for (refuel in confirmedInOrder(refuels)) {
        if (refuel.ts.isAfter(trip.start)) break
        val updated = tankPriceAfterRefuel(refuel, tankPrice, capacityL)
        if (updated != null) {
            tankPrice = updated
            usedRefuel = refuel
        }
    }

    val refuelId = usedRefuel?.id
    val price = tankPrice
    if (refuelId != null && price != null) {
        return TripCost(
            tripId = trip.id,
            costPln = roundMoney(trip.fuelL.multiply(price)),
            pricePerLiter = price,
            refuelId = refuelId,
            source = CostSource.REFUEL,
        )
    }

    if (default != null) {
        return TripCost(
            tripId = trip.id,
            costPln = roundMoney(trip.fuelL.multiply(default)),
            pricePerLiter = default,
            refuelId = null,
            source = CostSource.DEFAULT,
        )
    }

    return TripCost(
        tripId = trip.id,
        costPln = null,
        pricePerLiter = null,
        refuelId = null,
        source = CostSource.NONE,
    )
}

/**
 * Recompute costs for all trips in input order (retroactive recount after a
 * price is confirmed).
 */
fun recomputeCosts(
    trips: List<Trip>,
    refuels: Iterable<Refuel>,
    defaultPrice: BigDecimal? = null,
    capacityL: BigDecimal = DEFAULT_TANK_CAPACITY_L,
): List<TripCost> {
    val refuelList = refuels.toList()
    return trips.map { computeTripCost(it, refuelList, defaultPrice, capacityL) }
}

private fun confirmedInOrder(refuels: Iterable<Refuel>): List<Refuel> =
    refuels
        .filter { it.status == RefuelStatus.CONFIRMED }
        .sortedWith(compareBy<Refuel> { it.ts }.thenBy { it.id })
