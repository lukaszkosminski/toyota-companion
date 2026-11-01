package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal

/** A bucket under this many trips is a hint, not a comparison (owner decision on plan C14). */
const val WEATHER_MIN_TRIPS_PER_BUCKET: Int = 5

/** From this many millimetres a day counts as wet - drizzle is not a rainy day. Mirrors Python `WET_DAY_MM`. */
val WET_DAY_MM: BigDecimal = BigDecimal("0.5")

/** Reference of the temperature split: mild driving is what a surcharge is counted against. */
val REFERENCE_TEMPERATURE_BUCKET: TemperatureBucket = TemperatureBucket.MILD

/** Reference of the precipitation split. */
val REFERENCE_PRECIPITATION_BUCKET: PrecipitationBucket = PrecipitationBucket.DRY

private val HUNDRED = BigDecimal(100)
private val ZERO = BigDecimal(0)

/**
 * Bucket of a day by its mean temperature, half-open intervals (PRD F15, docs/SPEC.md §23):
 * frost is below 0 °C and a mean of exactly 0 °C is still COLD; the upper edge of a bucket
 * belongs to the next one, so 10 °C is MILD and 20 °C is WARM. `null` without a value.
 * Mirrors Python `temperature_bucket`.
 */
fun temperatureBucket(tempMean: BigDecimal?): TemperatureBucket? {
    if (tempMean == null) return null
    return when {
        tempMean < ZERO -> TemperatureBucket.FROST
        tempMean < BigDecimal(10) -> TemperatureBucket.COLD
        tempMean < BigDecimal(20) -> TemperatureBucket.MILD
        else -> TemperatureBucket.WARM
    }
}

/** A day is wet from [WET_DAY_MM] of precipitation inclusive; `null` without a value. Mirrors Python. */
fun precipitationBucket(precipMm: BigDecimal?): PrecipitationBucket? {
    if (precipMm == null) return null
    return if (precipMm >= WET_DAY_MM) PrecipitationBucket.WET else PrecipitationBucket.DRY
}

/**
 * Trips grouped by the temperature of their day, frost first (PRD F15, docs/SPEC.md §23).
 * [weather] is keyed by trip id - the `app` module resolves the rounded start point and the
 * local date of every trip and never hands the domain a coordinate. A trip with no entry
 * there, or with a day whose temperature the model did not give, is left out of the buckets
 * and counted in [WeatherImpact.tripsWithoutWeather]. Mirrors Python `weather_cost_by_temperature`.
 */
fun weatherCostByTemperature(
    trips: List<Trip>,
    costs: List<TripCost>,
    weather: Map<String, DayWeather>,
    reference: TemperatureBucket? = REFERENCE_TEMPERATURE_BUCKET,
    minTrips: Int = WEATHER_MIN_TRIPS_PER_BUCKET,
): WeatherImpact =
    impact(
        trips,
        costs,
        weather,
        { day -> temperatureBucket(day.tempMean)?.label },
        TemperatureBucket.entries.map { it.label },
        reference?.label,
        minTrips,
    )

/** The same trips grouped by a dry or a wet day, dry first. Mirrors Python `weather_cost_by_precipitation`. */
fun weatherCostByPrecipitation(
    trips: List<Trip>,
    costs: List<TripCost>,
    weather: Map<String, DayWeather>,
    reference: PrecipitationBucket? = REFERENCE_PRECIPITATION_BUCKET,
    minTrips: Int = WEATHER_MIN_TRIPS_PER_BUCKET,
): WeatherImpact =
    impact(
        trips,
        costs,
        weather,
        { day -> precipitationBucket(day.precipMm)?.label },
        PrecipitationBucket.entries.map { it.label },
        reference?.label,
        minTrips,
    )

/** Shared body of both splits: group the trips, sum each bucket, then measure the gap. */
private fun impact(
    trips: List<Trip>,
    costs: List<TripCost>,
    weather: Map<String, DayWeather>,
    bucketOf: (DayWeather) -> String?,
    order: List<String>,
    reference: String?,
    minTrips: Int,
): WeatherImpact {
    val byTrip = costs.associateBy { it.tripId }
    val groups = LinkedHashMap<String, MutableList<Pair<Trip, DayWeather>>>()
    for (name in order) groups[name] = mutableListOf()
    var withoutWeather = 0
    for (trip in trips) {
        val day = weather[trip.id]
        val name = day?.let(bucketOf)
        if (day == null || name == null) {
            withoutWeather++
        } else {
            groups.getValue(name).add(trip to day)
        }
    }

    val members = order.filter { groups.getValue(it).isNotEmpty() }.map { it to groups.getValue(it) }
    val totals = members.associate { (name, group) -> name to bucket(name, group, byTrip, minTrips) }
    val referenceRow = reference?.let { totals[it] }
    val referenceConsumption = referenceRow?.lPer100Km
    if (reference == null || referenceRow == null || referenceConsumption == null || referenceRow.tooFewTrips) {
        // No reference bucket, too few trips in it, or no values at all: differences stay hidden.
        return WeatherImpact(
            buckets = members.map { (name, _) -> totals.getValue(name) },
            totalTrips = trips.size,
            tripsWithoutWeather = withoutWeather,
            referenceBucket = null,
            referenceLPer100Km = null,
        )
    }

    val rows = members.map { (name, group) ->
        val gap = totals.getValue(name).lPer100Km?.minus(referenceConsumption)
        bucket(name, group, byTrip, minTrips, gap)
    }
    return WeatherImpact(
        buckets = rows,
        totalTrips = trips.size,
        tripsWithoutWeather = withoutWeather,
        referenceBucket = reference,
        referenceLPer100Km = referenceRow.lPer100Km,
    )
}

/** Sums of one bucket; [delta] is its difference in l/100 km against the reference. */
private fun bucket(
    name: String,
    members: List<Pair<Trip, DayWeather>>,
    byTrip: Map<String, TripCost>,
    minTrips: Int,
    delta: BigDecimal? = null,
): WeatherBucketStats {
    val trips = members.map { it.first }
    val distance = trips.fold(ZERO) { acc, trip -> acc.add(toBigDecimal(trip.distanceKm)) }
    val fuel = trips.fold(ZERO) { acc, trip -> acc.add(trip.fuelL) }
    val ev = trips.filter { it.evDistanceKm != null }.fold(ZERO) { acc, trip ->
        acc.add(toBigDecimal(requireNotNull(trip.evDistanceKm)))
    }
    val priced = trips.mapNotNull { trip ->
        byTrip[trip.id]?.takeIf { it.costPln != null && it.source != CostSource.NONE }?.let { trip to it }
    }
    val costSum = if (priced.isEmpty()) null else priced.fold(ZERO) { acc, (_, cost) -> acc.add(requireNotNull(cost.costPln)) }
    val pricedDistance = priced.fold(ZERO) { acc, (trip, _) -> acc.add(toBigDecimal(trip.distanceKm)) }
    val gusts = members.mapNotNull { it.second.windMaxKmh }
    return WeatherBucketStats(
        bucket = name,
        trips = trips.size,
        distanceKm = roundDistance(distance),
        fuelL = fuel,
        lPer100Km = if (distance > ZERO) roundConsumption(fuel.multiply(HUNDRED).divideLikePythonDecimal(distance)) else null,
        costPln = costSum?.let { roundMoney(it) },
        tripsWithoutCost = trips.size - priced.size,
        costPer100Km = if (costSum != null && pricedDistance > ZERO) {
            roundMoney(costSum.multiply(HUNDRED).divideLikePythonDecimal(pricedDistance))
        } else {
            null
        },
        evKm = roundDistance(ev),
        deltaLPer100Km = delta?.let { roundConsumption(it) },
        tooFewTrips = trips.size < minTrips,
        windMaxKmh = gusts.maxOrNull(),
    )
}
