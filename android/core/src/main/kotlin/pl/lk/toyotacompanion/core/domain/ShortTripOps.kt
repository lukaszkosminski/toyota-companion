package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * Short trips and the cold engine: fuel per 100 km by trip length (PRD F50, docs/SPEC.md §50). Mirrors
 * `backend/app/domain/shorttrips.py` (the same test names live in `backend/tests/test_domain_shorttrips.py`).
 *
 * Pure functions over the trips of one vehicle: the bands of length, the share of trips and km in each, litres per 100 km as
 * `sum(litres) / sum(km)` (never an average of averages), the excess over the reference (all trips from 10 km) and a cold-start
 * split by the gap since the previous trip ended.
 */

/** Below this distance a drive is moving the car in a car park, not a trip (plan C30). */
val SHORT_MIN_KM: BigDecimal = BigDecimal("0.1")

/** Trips from this length are the reference; the bands below it are "short". */
val SHORT_REFERENCE_FROM_KM: BigDecimal = BigDecimal(10)

/** A start at least this long after the previous trip ended is a cold start (an approximation, not a measurement). */
const val COLD_GAP_HOURS: Long = 3
val COLD_GAP: Duration = Duration.ofHours(COLD_GAP_HOURS)

/** A longer gap may be a hole in the archive, so the start is unknown (the same limit as the parking diary, F17). */
const val MAX_GAP_DAYS: Long = 21
val MAX_GAP: Duration = Duration.ofDays(MAX_GAP_DAYS)

/** A band shows its litres per 100 km from this many trips (as a cell of SPEC §28). */
const val SHORT_MIN_TRIPS_SHOWN: Int = 5

/** A conclusion (the excess, cold against warm) needs this many trips on every side compared (as SPEC §22). */
const val SHORT_MIN_TRIPS_COMPARE: Int = 10

/** Length of a trip, the lower bound of each band belongs to it. */
enum class LengthBand { UNDER_2, FROM_2, FROM_5, FROM_10, FROM_25 }

/** How the engine started: after a long stand, after a short one, or unknown. */
enum class StartKind { COLD, WARM, UNKNOWN }

/** The bands below the reference, in the order of the screen. */
val SHORT_BANDS: List<LengthBand> = listOf(LengthBand.UNDER_2, LengthBand.FROM_2, LengthBand.FROM_5)

/** Cold and warm starts of one band, only when both sides have enough trips. */
data class ColdSplit(
    val coldTrips: Int,
    val coldLPer100Km: BigDecimal,
    val warmTrips: Int,
    val warmLPer100Km: BigDecimal,
    val differenceLPer100Km: BigDecimal,
)

/** One band of trip length (SPEC §50). The rates are `null` below [SHORT_MIN_TRIPS_SHOWN] trips. */
data class BandStats(
    val band: LengthBand,
    val trips: Int,
    val distanceKm: BigDecimal,
    val tripSharePct: BigDecimal,
    val kmSharePct: BigDecimal,
    val fuelL: BigDecimal,
    val lPer100Km: BigDecimal?,
    val costPer100Km: BigDecimal?,
    val evSharePct: BigDecimal?,
    val excessLPer100Km: BigDecimal?,
    val excessL: BigDecimal?,
    val excessPln: BigDecimal?,
    val coldTrips: Int,
    val warmTrips: Int,
    val unknownStartTrips: Int,
    val coldSplit: ColdSplit?,
)

/** All trips from [SHORT_REFERENCE_FROM_KM] together. */
data class ReferenceStats(
    val trips: Int,
    val distanceKm: BigDecimal,
    val fuelL: BigDecimal,
    val lPer100Km: BigDecimal?,
    val costPer100Km: BigDecimal?,
)

/** The bands that have trips, the reference and the totals of the short bands (SPEC §50). */
data class ShortTripSummary(
    val bands: List<BandStats>,
    val reference: ReferenceStats?,
    val shortTrips: Int,
    val shortTripSharePct: BigDecimal?,
    val shortKmSharePct: BigDecimal?,
    val totalExcessL: BigDecimal?,
    val totalExcessPln: BigDecimal?,
    val usedTrips: Int,
    val leftOut: Int,
)

private val HUNDRED = BigDecimal(100)
private val ZERO = BigDecimal.ZERO
private const val TWO_PLACES = 2

private fun isUsable(trip: Trip): Boolean =
    trip.distanceKm.isFinite() && toBigDecimal(trip.distanceKm) >= SHORT_MIN_KM && trip.fuelL.signum() >= 0

/** The band of a trip; a distance below [SHORT_MIN_KM] or not finite is no trip (`IllegalArgumentException`). */
fun lengthBandOf(distanceKm: Double): LengthBand {
    require(distanceKm.isFinite()) { "distance must be finite" }
    return lengthBandOf(toBigDecimal(distanceKm))
}

fun lengthBandOf(distanceKm: BigDecimal): LengthBand {
    require(distanceKm >= SHORT_MIN_KM) { "distance is below the shortest trip" }
    return when {
        distanceKm >= BigDecimal(25) -> LengthBand.FROM_25
        distanceKm >= BigDecimal(10) -> LengthBand.FROM_10
        distanceKm >= BigDecimal(5) -> LengthBand.FROM_5
        distanceKm >= BigDecimal(2) -> LengthBand.FROM_2
        else -> LengthBand.UNDER_2
    }
}

/**
 * The start of every trip by its id, from ALL the trips (also those the bands leave out). The previous trip is the one before it by
 * `(start, id)`: the first has no previous (unknown), a gap longer than [MAX_GAP] is unknown too (a hole in the archive), a gap of
 * [COLD_GAP] or more is cold, anything shorter - also an overlap - is warm.
 */
fun tripStartKinds(trips: List<Trip>): Map<String, StartKind> {
    val ordered = trips.sortedWith(compareBy({ it.start }, { it.id }))
    val kinds = LinkedHashMap<String, StartKind>(ordered.size)
    for (index in ordered.indices) {
        val trip = ordered[index]
        if (index == 0) {
            kinds[trip.id] = StartKind.UNKNOWN
            continue
        }
        val gap = Duration.between(ordered[index - 1].end, trip.start)
        kinds[trip.id] = when {
            gap > MAX_GAP -> StartKind.UNKNOWN
            gap >= COLD_GAP -> StartKind.COLD
            else -> StartKind.WARM
        }
    }
    return kinds
}

private fun per100(fuel: BigDecimal, km: BigDecimal): BigDecimal = fuel.multiply(HUNDRED).divideLikePythonDecimal(km)

private fun percent(part: BigDecimal, whole: BigDecimal): BigDecimal =
    part.multiply(HUNDRED).divideLikePythonDecimal(whole).setScale(0, RoundingMode.HALF_UP)

private fun priced(trip: Trip, byTrip: Map<String, TripCost>): TripCost? {
    val cost = byTrip[trip.id] ?: return null
    return if (cost.costPln == null || cost.source == CostSource.NONE) null else cost
}

private fun kmOf(group: List<Trip>): BigDecimal = group.fold(ZERO) { sum, trip -> sum.add(toBigDecimal(trip.distanceKm)) }

private fun fuelOf(group: List<Trip>): BigDecimal = group.fold(ZERO) { sum, trip -> sum.add(trip.fuelL) }

private fun costPer100(group: List<Trip>, byTrip: Map<String, TripCost>): BigDecimal? {
    var costSum = ZERO
    var pricedKm = ZERO
    for (trip in group) {
        val amount = priced(trip, byTrip)?.costPln ?: continue
        costSum = costSum.add(amount)
        pricedKm = pricedKm.add(toBigDecimal(trip.distanceKm))
    }
    if (pricedKm.signum() <= 0) return null
    return roundMoney(costSum.multiply(HUNDRED).divideLikePythonDecimal(pricedKm))
}

private fun pricePerLiter(group: List<Trip>, byTrip: Map<String, TripCost>): BigDecimal? {
    var costSum = ZERO
    var fuelSum = ZERO
    for (trip in group) {
        val amount = priced(trip, byTrip)?.costPln ?: continue
        if (trip.fuelL.signum() > 0) {
            costSum = costSum.add(amount)
            fuelSum = fuelSum.add(trip.fuelL)
        }
    }
    if (fuelSum.signum() <= 0) return null
    return costSum.divideLikePythonDecimal(fuelSum)
}

private fun evShare(group: List<Trip>, km: BigDecimal): BigDecimal? {
    if (group.none { it.evDistanceKm != null }) return null
    var electric = ZERO
    for (trip in group) {
        val ev = trip.evDistanceKm ?: continue
        if (ev.isFinite() && ev > 0.0) electric = electric.add(toBigDecimal(minOf(ev, trip.distanceKm)))
    }
    return percent(electric, km)
}

private fun split(group: List<Trip>, kinds: Map<String, StartKind>): ColdSplit? {
    val cold = group.filter { kinds[it.id] == StartKind.COLD }
    val warm = group.filter { kinds[it.id] == StartKind.WARM }
    if (cold.size < SHORT_MIN_TRIPS_COMPARE || warm.size < SHORT_MIN_TRIPS_COMPARE) return null
    val coldRate = roundConsumption(per100(fuelOf(cold), kmOf(cold)))
    val warmRate = roundConsumption(per100(fuelOf(warm), kmOf(warm)))
    return ColdSplit(cold.size, coldRate, warm.size, warmRate, coldRate.subtract(warmRate))
}

/**
 * The trips by length band with the excess of the short ones over the trips from 10 km (SPEC §50). Trips with a distance below
 * [SHORT_MIN_KM], not finite, or with negative fuel are only counted in [ShortTripSummary.leftOut]; a trip with 0 l (driven on the
 * battery) stays in every sum. Money is only from the trips with a price (`source != NONE` and an amount); without any price it is
 * `null`, never 0 zl.
 */
fun shortTripSummary(trips: List<Trip>, costs: List<TripCost>): ShortTripSummary {
    val byTrip = costs.associateBy { it.tripId }
    val kinds = tripStartKinds(trips)
    val used = trips.filter { isUsable(it) }
    val totalKm = kmOf(used)
    val groups = LengthBand.entries.associateWith { ArrayList<Trip>() }
    for (trip in used) groups.getValue(lengthBandOf(trip.distanceKm)).add(trip)

    val referenceGroup = groups.getValue(LengthBand.FROM_10) + groups.getValue(LengthBand.FROM_25)
    var reference: ReferenceStats? = null
    var refKm = ZERO
    var refFuel = ZERO
    if (referenceGroup.isNotEmpty()) {
        refKm = kmOf(referenceGroup)
        refFuel = fuelOf(referenceGroup)
        val enough = referenceGroup.size >= SHORT_MIN_TRIPS_SHOWN
        reference = ReferenceStats(
            trips = referenceGroup.size,
            distanceKm = roundDistance(refKm),
            fuelL = roundLiters(refFuel),
            lPer100Km = if (enough) roundConsumption(per100(refFuel, refKm)) else null,
            costPer100Km = if (enough) costPer100(referenceGroup, byTrip) else null,
        )
    }

    val rows = ArrayList<BandStats>()
    for (band in LengthBand.entries) {
        val group = groups.getValue(band)
        if (group.isEmpty()) continue
        val km = kmOf(group)
        val fuel = fuelOf(group)
        val enough = group.size >= SHORT_MIN_TRIPS_SHOWN
        val rate = if (enough) roundConsumption(per100(fuel, km)) else null
        var excessRate: BigDecimal? = null
        var excessL: BigDecimal? = null
        var excessPln: BigDecimal? = null
        val referenceRate = reference?.lPer100Km
        if (band in SHORT_BANDS && group.size >= SHORT_MIN_TRIPS_COMPARE && reference != null &&
            reference.trips >= SHORT_MIN_TRIPS_COMPARE && referenceRate != null && rate != null
        ) {
            excessRate = rate.subtract(referenceRate)
            val excessRaw = fuel.subtract(refFuel.multiply(km).divideLikePythonDecimal(refKm), DIVIDE_CONTEXT)
            excessL = excessRaw.setScale(TWO_PLACES, RoundingMode.HALF_UP)
            val price = pricePerLiter(group, byTrip)
            excessPln = if (price != null) roundMoney(excessRaw.multiply(price, DIVIDE_CONTEXT)) else null
        }
        rows += BandStats(
            band = band,
            trips = group.size,
            distanceKm = roundDistance(km),
            tripSharePct = percent(BigDecimal(group.size), BigDecimal(used.size)),
            kmSharePct = percent(km, totalKm),
            fuelL = roundLiters(fuel),
            lPer100Km = rate,
            costPer100Km = if (enough) costPer100(group, byTrip) else null,
            evSharePct = if (enough) evShare(group, km) else null,
            excessLPer100Km = excessRate,
            excessL = excessL,
            excessPln = excessPln,
            coldTrips = group.count { kinds[it.id] == StartKind.COLD },
            warmTrips = group.count { kinds[it.id] == StartKind.WARM },
            unknownStartTrips = group.count { kinds[it.id] == StartKind.UNKNOWN },
            coldSplit = split(group, kinds),
        )
    }

    val shortGroup = SHORT_BANDS.flatMap { groups.getValue(it) }
    val counted = rows.filter { it.band in SHORT_BANDS && it.excessL != null }
    val totalL = if (counted.isEmpty()) null else counted.fold(ZERO) { sum, row -> sum.add(requireNotNull(row.excessL)) }
    val totalPln = if (counted.isNotEmpty() && counted.all { it.excessPln != null }) {
        counted.fold(ZERO) { sum, row -> sum.add(requireNotNull(row.excessPln)) }
    } else {
        null
    }
    return ShortTripSummary(
        bands = rows,
        reference = reference,
        shortTrips = shortGroup.size,
        shortTripSharePct = if (used.isNotEmpty()) percent(BigDecimal(shortGroup.size), BigDecimal(used.size)) else null,
        shortKmSharePct = if (used.isNotEmpty()) percent(kmOf(shortGroup), totalKm) else null,
        totalExcessL = totalL,
        totalExcessPln = totalPln,
        usedTrips = used.size,
        leftOut = trips.size - used.size,
    )
}
