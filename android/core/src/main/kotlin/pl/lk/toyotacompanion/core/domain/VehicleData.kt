package pl.lk.toyotacompanion.core.domain

import java.time.Instant

/**
 * Read-only data about the car that is not part of the trip archive (docs/SPEC.md §42-§44).
 * Toyota caches it in the cloud, so reading it never wakes the car (`refresh_status` stays banned).
 * Every field is nullable: a payload may miss a part, and the screens say "no data" instead of guessing.
 */

enum class DoorPosition { DRIVER, PASSENGER, REAR_LEFT, REAR_RIGHT, REAR_BACK, HOOD }

enum class WindowPosition { DRIVER, PASSENGER, REAR_LEFT, REAR_RIGHT }

/**
 * One component as Toyota reports it. [isOpen] and [isLocked] are null when the payload has no
 * such state (the hood reports open only); [observedAt] is when Toyota last saw that state.
 */
data class ComponentState(
    val isOpen: Boolean? = null,
    val isLocked: Boolean? = null,
    val observedAt: Instant? = null,
)

/** `/v1/vehicle/status`: per-door lock/open, per-window open and the number of active warnings. */
data class VehicleStatus(
    val lastUpdate: Instant? = null,
    val warnings: Int? = null,
    val doors: Map<DoorPosition, ComponentState> = emptyMap(),
    val windows: Map<WindowPosition, ComponentState> = emptyMap(),
)

/** `/v1/location`: the last known position; Toyota updates it when the car parks. */
data class VehicleLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val displayName: String? = null,
    val acquiredAt: Instant? = null,
    val lastUpdate: Instant? = null,
)

/** `/v1/vehiclehealth/status`: warning entries (count only) and whether oil information came. */
data class VehicleHealth(
    val warnings: Int? = null,
    val hasOilInfo: Boolean? = null,
    val lastUpdate: Instant? = null,
)
