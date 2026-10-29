package pl.lk.toyotacompanion.core.data

import java.math.BigDecimal
import java.time.Instant
import pl.lk.toyotacompanion.core.domain.DEFAULT_TANK_CAPACITY_L
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

/**
 * Persistent-layer types (docs/SPEC.md §7, §8).
 *
 * These types stay out of `core.domain`: the domain models exactly what the
 * Python reference implementation models, and the archive keeps Toyota fields
 * the cost logic never touches (PRD F5, F11-F13). Room maps 1:1 onto them.
 */

/** One vehicle from `GET /v2/vehicle/guid`, as archived (no GUID is secret, but it is not needed). */
data class VehicleRecord(
    val vin: String,
    val brand: String?,
    val modelName: String?,
    val fuelType: String?,
    val evVehicle: Boolean?,
    val firstSeen: Instant,
    val lastSeen: Instant,
)

/**
 * A stored trip: the domain [Trip] plus the archive columns that the cost
 * calculation ignores but PRD F5/F11-F13 must not lose (Toyota deletes trips
 * after ~12 months). [toTrip] is the only projection used by the domain.
 */
data class TripRecord(
    val trip: Trip,
    val vin: String,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val durationIdleS: Long? = null,
    val averageSpeed: Double? = null,
    val maxSpeed: Double? = null,
    val nightTrip: Boolean? = null,
    val scoreGlobal: Int? = null,
    val scoreAcceleration: Int? = null,
    val scoreBraking: Int? = null,
    val scoreAdvice: Int? = null,
    val scoreConstantSpeed: Int? = null,
    val hdcEvDistanceM: Long? = null,
    val hdcEvTimeS: Long? = null,
    val hdcEcoDistM: Long? = null,
    val hdcEcoTimeS: Long? = null,
    val hdcPowerDistM: Long? = null,
    val hdcPowerTimeS: Long? = null,
    val hdcChargeDistM: Long? = null,
    val hdcChargeTimeS: Long? = null,
    val syncedAt: Instant? = null,
) {
    val id: String get() = trip.id

    /** Domain view used for cost computation (docs/SPEC.md §4, §5). */
    fun toTrip(): Trip = trip

    /** Cost-free placeholder so partial mappings can be typed; costs live in [CostRepository]. */
    fun withCost(cost: TripCost?): StoredTrip = StoredTrip(this, cost)
}

/** A trip together with its last computed cost (read-model for list/CSV screens). */
data class StoredTrip(val record: TripRecord, val cost: TripCost?)

/**
 * Why the last synchronization failed. Persisted as a stable string.
 * [retryable] tells the WorkManager worker (app module) whether a later attempt
 * can help: 429/5xx and transport errors yes, wrong credentials never.
 */
enum class SyncErrorKind(val retryable: Boolean) {
    /** No stored credentials — the user has to log in first. */
    NO_CREDENTIALS(retryable = false),

    /** Authentication failed (bad credentials, Toyota AMI change) — needs the login form. */
    LOGIN(retryable = false),

    /** Data endpoint failed (429 after retries, 403, 5xx). */
    API(retryable = true),

    /** No reachable network / transport failure. */
    NETWORK(retryable = true),

    /** Unparseable or empty payload. */
    DATA(retryable = true),

    /** No vehicle on the account, or several and none selected. */
    NO_VEHICLE(retryable = false),
}

/**
 * Persisted synchronization bookkeeping (single row).
 *
 * [watermarkEpochDay] is the `to` date of the last fully successful trip fetch;
 * the next run fetches from `watermark - overlap` so trips that Toyota ingests
 * late are still picked up (docs/SPEC.md §8.3). It only advances on success.
 */
data class SyncState(
    val vin: String? = null,
    val watermarkEpochDay: Long? = null,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastErrorAt: Instant? = null,
    val lastErrorKind: SyncErrorKind? = null,
    val lastErrorMessage: String? = null,
) {
    /** Local data is on screen but not current (docs/ARCHITECTURE.md failure table). */
    val isStale: Boolean
        get() = lastSuccessAt == null || (lastErrorAt != null && lastErrorAt.isAfter(lastSuccessAt))

    /** Only a credentials problem needs the login form; API/network errors must not ask again. */
    val needsLogin: Boolean
        get() = lastErrorKind == SyncErrorKind.NO_CREDENTIALS || lastErrorKind == SyncErrorKind.LOGIN

    val needsVehicle: Boolean get() = vin == null
}

/** Configuration of one [pl.lk.toyotacompanion.core.sync.SyncEngine]. */
data class SyncConfig(
    /** How far back the very first sync asks for trips (Toyota retention is ~12 months). */
    val firstSyncWindowDays: Int = 365,

    /** Extra days re-fetched before the watermark for late-arriving trips. */
    val tripBookmarkOverlapDays: Long = 2,

    /** docs/SPEC.md §1 threshold passed to `detectRefuel`. */
    val minRefuelJumpPct: Int = 8,

    /** docs/SPEC.md §4/§6 capacity (PRD §7: 43 l). */
    val tankCapacityL: BigDecimal = DEFAULT_TANK_CAPACITY_L,

    /** docs/SPEC.md §4 fallback price for trips before the first refuel. */
    val defaultPrice: BigDecimal? = null,
)

/** What a read of the local archive plus sync status looks like (UI-facing, no Room types). */
data class SyncStatus(
    val state: SyncState,
    val tripsStored: Int,
    val readingsStored: Int,
    val pendingRefuels: Int,
) {
    val isFresh: Boolean get() = !state.isStale
}

// Refuels and fuel readings are stored per vehicle but are read back as the
// plain domain types (docs/SPEC.md §1-§6): `vin` is a query parameter on the
// repositories, never a field of the domain object (the Python reference has
// no vin either).
