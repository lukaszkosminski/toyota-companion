package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal

/**
 * Cost of a trip based on refuel prices.
 *
 * Picks the latest refuel with [RefuelStatus.CONFIRMED], a set
 * pricePerLiter and `ts <= trip.start`; cost is `trip.fuelL * price` rounded
 * to 0.01 zl (ROUND_HALF_UP), [CostSource.REFUEL]. With no such refuel but a
 * [defaultPrice], [CostSource.DEFAULT]. Otherwise everything is null and
 * [CostSource.NONE]. Pending/dismissed refuels are ignored.
 */
fun computeTripCost(
    trip: Trip,
    refuels: Iterable<Refuel>,
    defaultPrice: BigDecimal? = null,
): TripCost {
    var latest: Refuel? = null
    var latestPrice: BigDecimal? = null
    for (refuel in refuels) {
        val price = refuel.pricePerLiter
        if (refuel.status != RefuelStatus.CONFIRMED || price == null) continue
        if (refuel.ts.isAfter(trip.start)) continue
        if (latest == null || !refuel.ts.isBefore(latest.ts)) {
            latest = refuel
            latestPrice = price
        }
    }

    val refuelId = latest?.id
    val price = latestPrice
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
): List<TripCost> {
    val refuelList = refuels.toList()
    return trips.map { computeTripCost(it, refuelList, defaultPrice) }
}
