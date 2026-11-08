package pl.lk.toyotacompanion.core.toyota

import java.time.Instant
import pl.lk.toyotacompanion.core.domain.ComponentState
import pl.lk.toyotacompanion.core.domain.DoorPosition
import pl.lk.toyotacompanion.core.domain.VehicleHealth
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus
import pl.lk.toyotacompanion.core.domain.WindowPosition
import pl.lk.toyotacompanion.core.toyota.dto.ComponentStateDto
import pl.lk.toyotacompanion.core.toyota.dto.DoorDto
import pl.lk.toyotacompanion.core.toyota.dto.LocationDto
import pl.lk.toyotacompanion.core.toyota.dto.RemoteStatusDto
import pl.lk.toyotacompanion.core.toyota.dto.VehicleHealthDto

/**
 * Domain models of the read-only Toyota data (docs/SPEC.md §42-§44) out of the wire DTOs.
 * Unknown/missing parts become nulls, never exceptions: the screens say "no data" and stay usable.
 */
object VehicleDataMapper {

    /** `/v1/vehicle/status`; null when the payload itself is missing. */
    fun toVehicleStatus(dto: RemoteStatusDto?): VehicleStatus? {
        dto ?: return null
        val doors = buildMap {
            dto.doors?.driver?.toState()?.let { put(DoorPosition.DRIVER, it) }
            dto.doors?.passenger?.toState()?.let { put(DoorPosition.PASSENGER, it) }
            dto.doors?.rearLeft?.toState()?.let { put(DoorPosition.REAR_LEFT, it) }
            dto.doors?.rearRight?.toState()?.let { put(DoorPosition.REAR_RIGHT, it) }
            dto.doors?.rearBack?.toState()?.let { put(DoorPosition.REAR_BACK, it) }
            dto.doors?.hood?.toState()?.let { put(DoorPosition.HOOD, it) }
        }
        val windows = buildMap {
            dto.windows?.driver?.toState()?.let { put(WindowPosition.DRIVER, it) }
            dto.windows?.passenger?.toState()?.let { put(WindowPosition.PASSENGER, it) }
            dto.windows?.rearLeft?.toState()?.let { put(WindowPosition.REAR_LEFT, it) }
            dto.windows?.rearRight?.toState()?.let { put(WindowPosition.REAR_RIGHT, it) }
        }
        return VehicleStatus(
            lastUpdate = parseInstant(dto.lastUpdateTimestamp),
            warnings = dto.overallWarningCounts,
            doors = doors,
            windows = windows,
        )
    }

    /** `/v1/location`; null when the payload itself is missing. */
    fun toVehicleLocation(dto: LocationDto?): VehicleLocation? {
        dto ?: return null
        return VehicleLocation(
            latitude = dto.vehicleLocation?.latitude,
            longitude = dto.vehicleLocation?.longitude,
            displayName = dto.vehicleLocation?.displayName,
            acquiredAt = parseInstant(dto.vehicleLocation?.locationAcquisitionDatetime),
            lastUpdate = parseInstant(dto.lastTimestamp),
        )
    }

    /** `/v1/vehiclehealth/status`; null when the payload itself is missing. */
    fun toVehicleHealth(dto: VehicleHealthDto?): VehicleHealth? {
        dto ?: return null
        return VehicleHealth(
            warnings = dto.warning?.size,
            hasOilInfo = dto.quantityOfEngOilIcon != null,
            lastUpdate = parseInstant(dto.wnglastUpdTime),
        )
    }

    private fun DoorDto?.toState(): ComponentState? {
        this ?: return null
        return ComponentState(
            isOpen = openStatus?.status?.let(::openState),
            isLocked = lockStatus?.status?.let(::lockState),
            observedAt = parseInstant(openStatus?.lastUpdateTimestamp) ?: parseInstant(lockStatus?.lastUpdateTimestamp),
        )
    }

    private fun ComponentStateDto.toState(): ComponentState = ComponentState(
        isOpen = status?.let(::openState),
        observedAt = parseInstant(lastUpdateTimestamp),
    )

    /** pytoyoda status.py:18-22: `"open"` / `"close"` for open state. */
    private fun openState(status: String): Boolean? = when (status.lowercase()) {
        "open" -> true
        "close", "closed" -> false
        else -> null
    }

    /** pytoyoda status.py:18-22: `"locked"` / `"unlocked"` for locks. */
    private fun lockState(status: String): Boolean? = when (status.lowercase()) {
        "locked" -> true
        "unlocked" -> false
        else -> null
    }

    /** ISO-8601 as Toyota sends it; an unparseable value is simply unknown. */
    private fun parseInstant(text: String?): Instant? =
        if (text.isNullOrBlank()) null else try {
            Instant.parse(text)
        } catch (e: Exception) {
            null
        }
}
