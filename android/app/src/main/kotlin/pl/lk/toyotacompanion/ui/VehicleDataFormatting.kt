package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import pl.lk.toyotacompanion.core.domain.ComponentState
import pl.lk.toyotacompanion.core.domain.DoorPosition
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus
import pl.lk.toyotacompanion.core.domain.WindowPosition

/** One row of the vehicle status screen: the component and the state Toyota reports (docs/SPEC.md §42). */
data class ComponentLine(val name: String, val state: String)

/** Doors and hood that Toyota reported, in a fixed order; a part missing from the payload is simply absent. */
fun doorLines(status: VehicleStatus): List<ComponentLine> =
    DoorPosition.entries.mapNotNull { position ->
        status.doors[position]?.let { ComponentLine(Texts.doorName(position), componentStateText(it)) }
    }

/** Windows that Toyota reported, in a fixed order. */
fun windowLines(status: VehicleStatus): List<ComponentLine> =
    WindowPosition.entries.mapNotNull { position ->
        status.windows[position]?.let { ComponentLine(Texts.windowName(position), componentStateText(it)) }
    }

/** "otwarte · odblokowane" — only what Toyota reported; "brak danych" when it reported neither. */
fun componentStateText(state: ComponentState): String {
    val parts = listOfNotNull(
        state.isOpen?.let { Texts.componentOpen(it) },
        state.isLocked?.let { Texts.componentLocked(it) },
    )
    return if (parts.isEmpty()) Texts.COMPONENT_UNKNOWN else parts.joinToString(Texts.SEPARATOR)
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
