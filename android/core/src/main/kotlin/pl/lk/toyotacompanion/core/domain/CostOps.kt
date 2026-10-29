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
 */
fun computeTripCost(
    trip: Trip,
    refuels: Iterable<Refuel>,
    defaultPrice: BigDecimal? = null,
    capacityL: BigDecimal = DEFAULT_TANK_CAPACITY_L,
): TripCost {
    var tankPrice: BigDecimal? = null
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

    if (defaultPrice != null) {
        return TripCost(
            tripId = trip.id,
            costPln = roundMoney(trip.fuelL.multiply(defaultPrice)),
            pricePerLiter = defaultPrice,
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
