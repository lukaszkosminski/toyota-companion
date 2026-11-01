package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant

enum class RefuelStatus { PENDING, CONFIRMED, DISMISSED }

enum class CostSource { REFUEL, DEFAULT, NONE }

/** Where the refuel liters came from (F2a/F9). */
enum class LitersSource { RECEIPT, ESTIMATE, DERIVED }

/** Status of one fuel-economy interval, first match wins (PRD F9, SPEC §15). */
enum class EconomyStatus { OK, OPEN, PENDING_REFUEL, DISMISSED_REFUEL, MISSING_RECEIPT, NO_TRIPS, TOO_SHORT }

/** One fuel-level telemetry reading. */
data class FuelReading(
    val ts: Instant,
    val levelPct: Int,
    val odometerKm: Double,
    val batteryLevelPct: Int? = null,
    val distanceToEmptyKm: Double? = null,
)

/** A detected/confirmed refuel event. Immutable; use copy() for variants. */
data class Refuel(
    val id: String,
    val ts: Instant,
    val odometerKm: Double,
    val levelBefore: Int,
    val levelAfter: Int,
    val liters: BigDecimal?,
    val pricePerLiter: BigDecimal?,
    val totalCost: BigDecimal?,
    val status: RefuelStatus,
    val litersSource: LitersSource? = null,
    val filledToFull: Boolean? = null,
)

/** A driven trip. Immutable. */
data class Trip(
    val id: String,
    val start: Instant,
    val end: Instant,
    val distanceKm: Double,
    val fuelL: BigDecimal,
    val evDistanceKm: Double?,
)

/** Computed cost of a trip. Immutable. */
data class TripCost(
    val tripId: String,
    val costPln: BigDecimal?,
    val pricePerLiter: BigDecimal?,
    val refuelId: String?,
    val source: CostSource,
)

/**
 * Fuel economy between two full-tank refuels. Immutable (PRD F9, docs/SPEC.md §15).
 *
 * [endRefuelId] is null for the open interval after the last full refuel. Distances and
 * the computer side come from the trips that start in `[start.ts, end.ts)`; the actual
 * side from the liters of the confirmed refuels in `(start, end]`. The `*LPer100Km` and
 * [differencePct] values are set only where they mean something (see
 * [fuelEconomyIntervals]).
 */
data class EconomyInterval(
    val startRefuelId: String,
    val endRefuelId: String?,
    val status: EconomyStatus,
    val trips: Int,
    val distanceKm: BigDecimal,
    val evDistanceKm: BigDecimal,
    val computerLiters: BigDecimal,
    val actualLiters: BigDecimal?,
    val odometerKm: BigDecimal?,
    val computerLPer100Km: BigDecimal?,
    val actualLPer100Km: BigDecimal?,
    val differencePct: BigDecimal?,
)

/** Electric share of one trip (PRD F11); [sharePct] is `null` without Toyota's `hdc` data. Mirrors Python `TripShare`. */
data class TripShare(
    val tripId: String,
    val distanceKm: BigDecimal,
    val evDistanceKm: BigDecimal?,
    val sharePct: BigDecimal?,
    val inconsistent: Boolean,
)

/** The electric ranking and what it left out (PRD F11). Mirrors Python `ElectricRanking`. */
data class ElectricRanking(val rows: List<TripShare>, val withoutData: Int, val tooShort: Int)

enum class PeriodKind { YEAR, SEASON }

/** Meteorological season; December counts to the winter of the next year. */
enum class Season { WINTER, SPRING, SUMMER, AUTUMN }

/** Sums of one year or one season (PRD F10, docs/SPEC.md §17). Mirrors Python `PeriodStats`. */
data class PeriodStats(
    val year: Int,
    val season: Season?,
    val trips: Int,
    val distanceKm: BigDecimal,
    val fuelL: BigDecimal,
    val lPer100Km: BigDecimal?,
    val costPln: BigDecimal?,
    val tripsWithoutCost: Int,
    val costPer100Km: BigDecimal?,
    val evSharePct: BigDecimal?,
    val averageTripKm: BigDecimal?,
)

/** Fuel left now and how far and how long it lasts until the reserve (PRD F14). Mirrors Python `RefuelForecast`. */
data class RefuelForecast(
    val litersLeft: BigDecimal,
    val levelPct: BigDecimal,
    val lPer100Km: BigDecimal,
    val dailyKm: BigDecimal,
    val kmToReserve: BigDecimal,
    val daysToReserve: BigDecimal,
)

/** Bucket of a day by its mean temperature (PRD F15). `label` is the string the domain reports. */
enum class TemperatureBucket(val label: String) {
    FROST("frost"),
    COLD("cold"),
    MILD("mild"),
    WARM("warm"),
}

/** Bucket of a day by its precipitation (PRD F15). */
enum class PrecipitationBucket(val label: String) {
    DRY("dry"),
    WET("wet"),
}

/**
 * Reanalysis of one day at one place (PRD F15, docs/SPEC.md §23). Every value is `null` when
 * the model had none. A day without values is NOT a zero: a missing temperature must not turn
 * a frosty day into a mild one, so such a trip is left out of the buckets and only counted.
 * Mirrors Python `DayWeather`.
 */
data class DayWeather(
    val tempMean: BigDecimal? = null,
    val tempMin: BigDecimal? = null,
    val tempMax: BigDecimal? = null,
    val precipMm: BigDecimal? = null,
    val precipHours: BigDecimal? = null,
    val windMaxKmh: BigDecimal? = null,
)

/**
 * One weather bucket: sums of its trips, l/100 km, zł/100 km and the gap to the reference
 * (PRD F15). `costPer100Km` is `null` when no trip in the bucket has a price, and is then
 * always counted over the kilometres of the priced trips only. `deltaLPer100Km` is `null`
 * without a usable reference bucket. `windMaxKmh` is reported and never used for a verdict.
 * Mirrors Python `WeatherBucketStats`.
 */
data class WeatherBucketStats(
    val bucket: String,
    val trips: Int,
    val distanceKm: BigDecimal,
    val fuelL: BigDecimal,
    val lPer100Km: BigDecimal?,
    val costPln: BigDecimal?,
    val tripsWithoutCost: Int,
    val costPer100Km: BigDecimal?,
    val evKm: BigDecimal,
    val deltaLPer100Km: BigDecimal?,
    val tooFewTrips: Boolean,
    val windMaxKmh: BigDecimal?,
)

/**
 * Buckets in their natural order (frost first, dry first) plus the trips that got no weather
 * at all (PRD F15). `referenceBucket` and `referenceLPer100Km` are `null` when the reference
 * bucket is missing or too small - then no bucket carries a difference either. Mirrors Python
 * `WeatherImpact`.
 */
data class WeatherImpact(
    val buckets: List<WeatherBucketStats>,
    val totalTrips: Int,
    val tripsWithoutWeather: Int,
    val referenceBucket: String?,
    val referenceLPer100Km: BigDecimal?,
)
