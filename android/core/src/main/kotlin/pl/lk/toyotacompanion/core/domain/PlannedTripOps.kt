package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Trip cost calculator (PRD F52, docs/SPEC.md §52): litres and zloty of a planned trip from the own archive, as a range between the
 * lower and the upper quartile of the consumption of trips of one kind. Mirrors `backend/app/domain/planned.py` (the same test names
 * live in `backend/tests/test_domain_planned.py`).
 */

/** Trips shorter than this are no sample of a kind, as in F19 (docs/SPEC.md §22). */
val PLAN_MIN_KM: BigDecimal = BigDecimal("2")

/** The fewest trips of a kind that give a range, as in F19 (docs/SPEC.md §22). */
const val PLAN_MIN_TRIPS: Int = 10

/** The similar-length population is used when it has at least this many trips, otherwise the whole kind. */
const val PLAN_SIMILAR_MIN_TRIPS: Int = 10

/** Similar length: from this share of the planned distance up to this multiple of it (inclusive). */
val PLAN_SIMILAR_LOW: BigDecimal = BigDecimal("0.5")
val PLAN_SIMILAR_HIGH: BigDecimal = BigDecimal("2")

/** The longest planned trip accepted, in km. */
val PLAN_MAX_KM: BigDecimal = BigDecimal("5000")

private val HUNDRED = BigDecimal(100)
private val ZERO = BigDecimal.ZERO

/** One trip of the archive as the calculator sees it: its distance, its litres and its kind of drive (by the average speed). */
data class PlanSample(val distanceKm: BigDecimal, val fuelL: BigDecimal, val band: TrafficClass)

/** Which trips the range was taken from: those of a similar length, or all trips of the kind. */
enum class PlanPopulation { SIMILAR_LENGTH, WHOLE_KIND }

/**
 * The range of a planned trip. [trips] is the size of the population the quartiles came from and [kindTrips] all trips of the kind.
 * [litresLow] and [litresHigh] are 0.1 l, [costLow] and [costHigh] whole zloty, `null` without a price.
 */
data class PlannedRange(
    val band: TrafficClass,
    val population: PlanPopulation,
    val trips: Int,
    val kindTrips: Int,
    val litresLow: BigDecimal,
    val litresHigh: BigDecimal,
    val costLow: BigDecimal?,
    val costHigh: BigDecimal?,
)

/** The outcome: a range, too few trips of the kind, or a distance that is no trip. */
sealed interface PlannedTripResult {
    data object BadKm : PlannedTripResult

    data class TooFew(val trips: Int) : PlannedTripResult

    data class Range(val range: PlannedRange) : PlannedTripResult
}

/**
 * The range for [km] of a trip of kind [band] (PRD F52). Only samples of that kind with at least [PLAN_MIN_KM] and fuel ≥ 0 count; trips with
 * 0 l stay in (they are driven on the battery, as in F19). The population is the trips of a similar length when there are at least
 * [PLAN_SIMILAR_MIN_TRIPS] of them, otherwise all trips of the kind; below [PLAN_MIN_TRIPS] trips of the kind there is no range.
 *
 * Each trip gives its rate `litres * 100 / km`; the rates are sorted, and the lower quartile is the rate at index `(n-1)/4` and the upper one
 * at `3*(n-1)/4` (integer division, no interpolation). Litres are `km * rate / 100` with the unrounded rate, zloty are the unrounded litres times
 * the price.
 */
fun plannedTripCost(
    samples: List<PlanSample>,
    km: BigDecimal,
    band: TrafficClass,
    pricePerLiter: BigDecimal?,
): PlannedTripResult {
    if (km.signum() <= 0 || km > PLAN_MAX_KM) return PlannedTripResult.BadKm
    val kind = samples.filter { it.band == band && it.distanceKm > ZERO && it.distanceKm >= PLAN_MIN_KM && it.fuelL.signum() >= 0 }
    if (kind.size < PLAN_MIN_TRIPS) return PlannedTripResult.TooFew(kind.size)
    val low = km.multiply(PLAN_SIMILAR_LOW)
    val high = km.multiply(PLAN_SIMILAR_HIGH)
    val similar = kind.filter { it.distanceKm >= low && it.distanceKm <= high }
    val population = if (similar.size >= PLAN_SIMILAR_MIN_TRIPS) similar else kind
    val rates = population.map { it.fuelL.multiply(HUNDRED).divideLikePythonDecimal(it.distanceKm) }.sorted()
    val lowRate = rates[(rates.size - 1) / 4]
    val highRate = rates[3 * (rates.size - 1) / 4]
    val litresLow = km.multiply(lowRate).divideLikePythonDecimal(HUNDRED)
    val litresHigh = km.multiply(highRate).divideLikePythonDecimal(HUNDRED)
    return PlannedTripResult.Range(
        PlannedRange(
            band = band,
            population = if (similar.size >= PLAN_SIMILAR_MIN_TRIPS) PlanPopulation.SIMILAR_LENGTH else PlanPopulation.WHOLE_KIND,
            trips = population.size,
            kindTrips = kind.size,
            litresLow = litresLow.setScale(1, RoundingMode.HALF_UP),
            litresHigh = litresHigh.setScale(1, RoundingMode.HALF_UP),
            costLow = pricePerLiter?.let { litresLow.multiply(it).setScale(0, RoundingMode.HALF_UP) },
            costHigh = pricePerLiter?.let { litresHigh.multiply(it).setScale(0, RoundingMode.HALF_UP) },
        ),
    )
}

/**
 * The kind of drive with the most samples, the default of the calculator (PRD F52). A tie goes to the kind that comes first in
 * [TrafficClass]; `null` without any sample of at least [PLAN_MIN_KM].
 */
fun mostDrivenBand(samples: List<PlanSample>): TrafficClass? {
    val counts = samples.filter { it.distanceKm >= PLAN_MIN_KM && it.fuelL.signum() >= 0 }.groupingBy { it.band }.eachCount()
    return TrafficClass.entries.filter { counts.containsKey(it) }.maxByOrNull { counts.getValue(it) }
}
