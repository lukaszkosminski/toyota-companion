package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant

enum class RefuelStatus { PENDING, CONFIRMED, DISMISSED }

enum class CostSource { REFUEL, DEFAULT, NONE }

/** One fuel-level telemetry reading. */
data class FuelReading(
    val ts: Instant,
    val levelPct: Int,
    val odometerKm: Double,
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
