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
