package pl.lk.toyotacompanion.core.toyota

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import pl.lk.toyotacompanion.core.domain.ComponentState
import pl.lk.toyotacompanion.core.domain.DoorPosition
import pl.lk.toyotacompanion.core.domain.ServiceHistory
import pl.lk.toyotacompanion.core.domain.ServiceRecord
import pl.lk.toyotacompanion.core.domain.ToyotaNotification
import pl.lk.toyotacompanion.core.domain.VehicleHealth
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus
import pl.lk.toyotacompanion.core.domain.WindowPosition
import pl.lk.toyotacompanion.core.toyota.dto.ComponentStateDto
import pl.lk.toyotacompanion.core.toyota.dto.DoorDto
import pl.lk.toyotacompanion.core.toyota.dto.LocationDto
import pl.lk.toyotacompanion.core.toyota.dto.NotificationDto
import pl.lk.toyotacompanion.core.toyota.dto.NotificationPageDto
import pl.lk.toyotacompanion.core.toyota.dto.RemoteStatusDto
import pl.lk.toyotacompanion.core.toyota.dto.ServiceHistoryDto
import pl.lk.toyotacompanion.core.toyota.dto.ServiceHistoryPayloadDto
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

    /**
     * `/v1/servicehistory/vehicle/summary` (docs/SPEC.md §45); null when the payload itself is missing.
     * Newest first; a record without a date sorts last instead of being dropped.
     *
     * Shown: date, mileage (with Toyota's unit), category, operations and the dealer. NOT shown (owner's
     * decision 2026-11-09): `serviceHistoryId`, `roNumber`, `notes` and `customerCreatedRecord`.
     */
    fun toServiceHistory(dto: ServiceHistoryPayloadDto?): ServiceHistory? {
        dto ?: return null
        val records = dto.serviceHistories.orEmpty().mapNotNull { it?.toRecord() }
        return ServiceHistory(
            records = records.sortedWith(
                compareByDescending<ServiceRecord> { it.date }.thenByDescending { it.mileage },
            ),
        )
    }

    /**
     * `/v2/notification/history` (docs/SPEC.md §46); null when Toyota sent no payload at all.
     * Pages are flattened, the newest come first and every VIN inside a message is masked.
     */
    fun toNotifications(pages: List<NotificationPageDto?>?, vin: String?): List<ToyotaNotification>? {
        pages ?: return null
        return pages.asSequence()
            .filterNotNull()
            .flatMap { page ->
                page.notifications.orEmpty().asSequence().filterNotNull()
                    .map { it.toNotification(page.vin ?: vin) }
            }
            .sortedWith(compareByDescending<ToyotaNotification> { it.date })
            .toList()
    }

    /**
     * One service record. `servicingDealer` is `Any` in the reference, so only readable text is used;
     * when it carries none, `serviceProvider` takes its place under the same "Dealer" label.
     */
    private fun ServiceHistoryDto.toRecord(): ServiceRecord = ServiceRecord(
        date = parseDate(serviceDate),
        mileage = mileage,
        mileageUnit = unit?.trim()?.takeIf { it.isNotEmpty() },
        category = serviceCategory?.trim()?.takeIf { it.isNotEmpty() },
        operations = operationsPerformed.toTextList(),
        dealer = servicingDealer.toText() ?: serviceProvider?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun NotificationDto.toNotification(vin: String?): ToyotaNotification = ToyotaNotification(
        date = parseInstant(notificationDate),
        category = displayCategory?.trim()?.takeIf { it.isNotEmpty() } ?: category?.trim()?.takeIf { it.isNotEmpty() },
        type = type?.trim()?.takeIf { it.isNotEmpty() },
        message = message?.let { maskVin(it, this.vin ?: vin) },
        isRead = isRead,
    )

    /** The VIN inside a message keeps only its last four characters (docs/SECURITY.md); a full VIN is never shown. */
    private fun maskVin(text: String, vin: String?): String {
        if (vin.isNullOrBlank() || !text.contains(vin)) return text
        return text.replace(vin, maskedVin(vin))
    }

    private fun maskedVin(vin: String): String = "*".repeat((vin.length - 4).coerceAtLeast(0)) + vin.takeLast(4)

    /** A string, or the strings of an array. Anything else is "not readable" (null), never an interpretation. */
    private fun JsonElement?.toTextList(): List<String>? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.let(::listOf)
        is JsonArray -> {
            val strings = filterIsInstance<JsonPrimitive>().filter { it.isString }
                .map { it.content.trim() }.filter { it.isNotEmpty() }
            // An empty array is an empty list; an array with no text at all is "not readable".
            if (strings.isEmpty() && isNotEmpty()) null else strings
        }
        else -> null
    }

    /** The first readable text of a value that may be a string, an array of strings or something else. */
    private fun JsonElement?.toText(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        is JsonArray -> filterIsInstance<JsonPrimitive>()
            .firstNotNullOfOrNull { primitive ->
                primitive.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            }
        else -> null
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

    /** ISO-8601 as Toyota sends it; an unparseable value is simply unknown (no guessed zone). */
    private fun parseInstant(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return try {
            Instant.parse(text)
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** `serviceDate` is a plain ISO date; anything else stays unknown. */
    private fun parseDate(text: String?): LocalDate? =
        if (text.isNullOrBlank()) null else try {
            LocalDate.parse(text)
        } catch (e: Exception) {
            null
        }
}
