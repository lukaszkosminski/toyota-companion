package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.ComponentState
import pl.lk.toyotacompanion.core.domain.DoorPosition
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus
import pl.lk.toyotacompanion.core.domain.WindowPosition

/**
 * One row of the vehicle status screen: the component and the state Toyota reports (docs/SPEC.md §42). [deviation] marks a component
 * that is open or unlocked - the screen draws it bold in the warning colour.
 */
data class ComponentLine(val name: String, val state: String, val deviation: Boolean = false)

/** Open or unlocked: the only states that are worth a second look on a parked car. */
fun isDeviation(state: ComponentState): Boolean = state.isOpen == true || state.isLocked == false

/** A component for which Toyota reported neither an open nor a locked state. */
private fun hasNoState(state: ComponentState): Boolean = state.isOpen == null && state.isLocked == null

/** What the top of the vehicle status card says (docs/SPEC.md §42): everything shut and locked, or how many parts need a look. */
sealed interface VehicleSummary {
    data object AllClosed : VehicleSummary

    data class Deviations(val count: Int) : VehicleSummary
}

/**
 * [VehicleSummary.Deviations] with the number of open or unlocked doors, hood and windows; [VehicleSummary.AllClosed] only when at least
 * one component was reported, none deviates and none is without a state (a part with no data is not "closed"); otherwise `null`.
 */
fun vehicleSummary(status: VehicleStatus): VehicleSummary? {
    val states = status.doors.values + status.windows.values
    val deviations = states.count { isDeviation(it) }
    return when {
        deviations > 0 -> VehicleSummary.Deviations(deviations)
        states.isNotEmpty() && states.none { hasNoState(it) } -> VehicleSummary.AllClosed
        else -> null
    }
}

/** The sentence for [vehicleSummary]; `null` without one. */
fun vehicleSummaryText(status: VehicleStatus): String? = when (val summary = vehicleSummary(status)) {
    VehicleSummary.AllClosed -> Texts.VEHICLE_ALL_CLOSED
    is VehicleSummary.Deviations -> Texts.vehicleDeviations(summary.count)
    null -> null
}

/** Doors and hood that Toyota reported, in a fixed order; a part missing from the payload is simply absent. */
fun doorLines(status: VehicleStatus): List<ComponentLine> =
    DoorPosition.entries.mapNotNull { position ->
        status.doors[position]?.let { ComponentLine(Texts.doorName(position), componentStateText(it), isDeviation(it)) }
    }

/** Windows that Toyota reported, in a fixed order. */
fun windowLines(status: VehicleStatus): List<ComponentLine> =
    WindowPosition.entries.mapNotNull { position ->
        status.windows[position]?.let { ComponentLine(Texts.windowName(position), componentStateText(it), isDeviation(it)) }
    }

/** "otwarte · odblokowane" — only what Toyota reported; "brak danych" when it reported neither. */
fun componentStateText(state: ComponentState): String {
    val parts = listOfNotNull(
        state.isOpen?.let { Texts.componentOpen(it) },
        state.isLocked?.let { Texts.componentLocked(it) },
    )
    return if (parts.isEmpty()) Texts.COMPONENT_UNKNOWN else parts.joinToString(Texts.SEPARATOR)
}

/** The second date of the location screen ("Ostatnia aktualizacja") is shown only when it differs from the position time by at least this much. */
val LOCATION_UPDATE_GAP: Duration = Duration.ofHours(1)

/**
 * Toyota's own name of the position, in the app's language where it is known: `Last Parked` reads "Ostatnie parkowanie". Any other text is
 * shown as Toyota sent it - the reference documents the field only as a "human-readable name", so no other value is translated by guess.
 */
fun locationNameText(name: String?): String? {
    val text = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (text.equals("Last Parked", ignoreCase = true)) Texts.LOCATION_LAST_PARKED else text
}

/**
 * The time lines of the location screen: "Pozycja z ..." and, only when the last update differs from it by [LOCATION_UPDATE_GAP] or more
 * (or there is no position time), "Ostatnia aktualizacja ...". A minute's difference is the same moment, not a second fact.
 */
fun locationTimeLines(location: VehicleLocation, zone: ZoneId): List<String> {
    val acquired = location.acquiredAt
    val updated = location.lastUpdate
    val lines = ArrayList<String>(2)
    if (acquired != null) lines += Texts.locationAcquired(TripsFormatting.startDateTime(acquired, zone))
    if (updated != null && (acquired == null || Duration.between(acquired, updated).abs() >= LOCATION_UPDATE_GAP)) {
        lines += Texts.locationLastUpdate(TripsFormatting.startDateTime(updated, zone))
    }
    return lines
}

/**
 * The name of the known place ("Dom", "Praca", docs/SPEC.md §21) the position lies within [PARKING_MATCH_M] of, or `null`. The comparison
 * runs in memory only: the exact position is never stored or logged (ADR-022). When both places are that close, home wins.
 */
fun knownPlaceNear(latitude: Double?, longitude: Double?, known: KnownPlaces?): String? {
    if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() || known == null) return null
    fun near(place: ParkingPlace?) = place != null && distanceMeters(latitude, longitude, place.lat, place.lon) <= PARKING_MATCH_M
    return when {
        near(known.home) -> Texts.PLACE_HOME
        near(known.work) -> Texts.PLACE_WORK
        else -> null
    }
}

/** Coordinates with dot decimals to 5 places (about 1 m) — the exact last position, not rounded for privacy (ADR-022). */
fun coordinatesText(location: VehicleLocation): String? {
    val latitude = location.latitude ?: return null
    val longitude = location.longitude ?: return null
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    return Texts.locationCoords(coordinate(latitude), coordinate(longitude))
}

/** From the decimal text of the value (`BigDecimal.valueOf`), so 54.3685 gives 54.36850, not a binary artefact. */
private fun coordinate(value: Double): String =
    BigDecimal.valueOf(value).setScale(5, RoundingMode.HALF_UP).toPlainString()
