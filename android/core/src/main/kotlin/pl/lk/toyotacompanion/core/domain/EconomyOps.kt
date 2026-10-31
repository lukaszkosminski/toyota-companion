package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal

/** Below this trip distance an interval is too short to compare (owner decision D8). */
val MIN_COMPARABLE_DISTANCE_KM: BigDecimal = BigDecimal("100")

/**
 * Liters that measure fuel taken: from the receipt, or derived from its amount and price
 * (owner decision D7). A sensor estimate never checks the car's computer (PRD F2a).
 */
private val COMPARABLE_SOURCES: Set<LitersSource> = setOf(LitersSource.RECEIPT, LitersSource.DERIVED)

private val HUNDRED: BigDecimal = BigDecimal(100)
private val ZERO: BigDecimal = BigDecimal(0)

/**
 * Mirrors Python `fuel_economy_intervals` (app.domain.economy): intervals between
 * consecutive full-tank refuels of **one** vehicle, oldest first.
 *
 * An interval opens at a confirmed refuel with [filledToFull] and closes at the next one.
 * Its actual fuel is the sum of the liters of the confirmed refuels in `(start, end]`
 * (partial ones in between and the closing one); distance and the computer's fuel are sums
 * over the trips that *start* in `[start.ts, end.ts)` - the same trips on both sides, so
 * the delay with which a refuel is detected moves both sides alike. A trip that crosses the
 * end belongs where it started.
 *
 * Status, first match wins: [EconomyStatus.PENDING_REFUEL] (a refuel inside still waits for
 * a price), [EconomyStatus.DISMISSED_REFUEL] (fuel went in but has no liters),
 * [EconomyStatus.MISSING_RECEIPT] (a refuel inside has no liters, or only a sensor
 * estimate), [EconomyStatus.NO_TRIPS] (no distance or no fuel on the trips),
 * [EconomyStatus.TOO_SHORT] (distance below [minDistanceKm]), else [EconomyStatus.OK].
 * After the last full refuel one [EconomyStatus.OPEN] interval follows, with the trips so
 * far. Refuels before the first full one open nothing.
 *
 * Economy figures: [EconomyInterval.computerLPer100Km] whenever there is distance;
 * [EconomyInterval.actualLPer100Km] and [EconomyInterval.differencePct] only for
 * [EconomyStatus.OK]. The difference is `(actual - computer) / computer` from the liters,
 * rounded to 0.1 %. The odometer difference is informational and null when it is not
 * positive.
 */
fun fuelEconomyIntervals(
    refuels: List<Refuel>,
    trips: List<Trip>,
    minDistanceKm: BigDecimal = MIN_COMPARABLE_DISTANCE_KM,
): List<EconomyInterval> {
    val ordered = refuels.sortedWith(compareBy({ it.ts }, { it.id }))
    val orderedTrips = trips.sortedWith(compareBy({ it.start }, { it.id }))
    val anchors = ordered.indices.filter { index ->
        val refuel = ordered[index]
        refuel.status == RefuelStatus.CONFIRMED && refuel.filledToFull == true && refuel.liters != null
    }

    val intervals = mutableListOf<EconomyInterval>()
    for (position in anchors.indices) {
        val start = ordered[anchors[position]]
        if (position + 1 < anchors.size) {
            val end = ordered[anchors[position + 1]]
            val inside = ordered.subList(anchors[position] + 1, anchors[position + 1] + 1)
            val inWindow = orderedTrips.filter { start.ts <= it.start && it.start < end.ts }
            intervals += closedInterval(start, end, inside, inWindow, minDistanceKm)
        } else {
            val inWindow = orderedTrips.filter { it.start >= start.ts }
            intervals += openInterval(start, inWindow)
        }
    }
    return intervals
}

/** Distance, electric distance and computer fuel of one trip window. */
private fun totals(
    inWindow: List<Trip>,
): Triple<BigDecimal, BigDecimal, BigDecimal> {
    val distance = inWindow.fold(ZERO) { acc, trip -> acc.add(toBigDecimal(trip.distanceKm)) }
    val ev = inWindow.fold(ZERO) { acc, trip ->
        val km = trip.evDistanceKm
        if (km == null) acc else acc.add(toBigDecimal(km))
    }
    val fuel = inWindow.fold(ZERO) { acc, trip -> acc.add(trip.fuelL) }
    return Triple(distance, ev, fuel)
}

private fun per100(liters: BigDecimal, distance: BigDecimal): BigDecimal =
    roundConsumption(liters.multiply(HUNDRED).divideLikePythonDecimal(distance))

private fun closedInterval(
    start: Refuel,
    end: Refuel,
    inside: List<Refuel>,
    inWindow: List<Trip>,
    minDistanceKm: BigDecimal,
): EconomyInterval {
    val (distance, ev, fuel) = totals(inWindow)
    val confirmed = inside.filter { it.status == RefuelStatus.CONFIRMED }
    val measured = confirmed.all { it.liters != null && it.litersSource in COMPARABLE_SOURCES }
    val actual =
        if (measured) confirmed.mapNotNull { it.liters }.fold(ZERO, BigDecimal::add) else null

    val status = when {
        inside.any { it.status == RefuelStatus.PENDING } -> EconomyStatus.PENDING_REFUEL
        inside.any { it.status == RefuelStatus.DISMISSED } -> EconomyStatus.DISMISSED_REFUEL
        !measured -> EconomyStatus.MISSING_RECEIPT
        distance <= ZERO || fuel <= ZERO -> EconomyStatus.NO_TRIPS
        distance < minDistanceKm -> EconomyStatus.TOO_SHORT
        else -> EconomyStatus.OK
    }

    val odometer = toBigDecimal(end.odometerKm).subtract(toBigDecimal(start.odometerKm))
    val compare = status == EconomyStatus.OK && actual != null
    val showActual = actual != null &&
        status in setOf(EconomyStatus.OK, EconomyStatus.TOO_SHORT, EconomyStatus.NO_TRIPS)
    return EconomyInterval(
        startRefuelId = start.id,
        endRefuelId = end.id,
        status = status,
        trips = inWindow.size,
        distanceKm = roundDistance(distance),
        evDistanceKm = roundDistance(ev),
        computerLiters = fuel,
        actualLiters = if (showActual) actual else null,
        odometerKm = if (odometer > ZERO) roundDistance(odometer) else null,
        computerLPer100Km = if (distance > ZERO) per100(fuel, distance) else null,
        actualLPer100Km = if (compare && actual != null) per100(actual, distance) else null,
        differencePct = if (compare && actual != null) {
            roundPercent(actual.subtract(fuel).multiply(HUNDRED).divideLikePythonDecimal(fuel))
        } else {
            null
        },
    )
}

private fun openInterval(start: Refuel, inWindow: List<Trip>): EconomyInterval {
    val (distance, ev, fuel) = totals(inWindow)
    return EconomyInterval(
        startRefuelId = start.id,
        endRefuelId = null,
        status = EconomyStatus.OPEN,
        trips = inWindow.size,
        distanceKm = roundDistance(distance),
        evDistanceKm = roundDistance(ev),
        computerLiters = fuel,
        actualLiters = null,
        odometerKm = null,
        computerLPer100Km = if (distance > ZERO) per100(fuel, distance) else null,
        actualLPer100Km = null,
        differencePct = null,
    )
}
