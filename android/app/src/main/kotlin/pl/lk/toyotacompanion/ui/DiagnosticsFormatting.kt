package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.toyota.ToyotaSecrets

/**
 * The diagnostics screen and the text it hands to the share sheet (PRD F46, docs/SPEC.md §47).
 *
 * Everything here is a local read: no Toyota call, so the car is never woken. The report never carries a VIN,
 * a GPS coordinate or a token; the stored error message is sanitized when written and again when shown.
 */

/** `YYYY-MM-DD HH:mm` in [zone], or "brak" when Toyota/the archive recorded nothing. No `String.format` (C22 D7). */
fun diagnosticsTimestamp(at: Instant?, zone: ZoneId): String {
    at ?: return Texts.DIAGNOSTICS_NONE
    val local = at.atZone(zone)
    fun two(n: Int) = n.toString().padStart(2, '0')
    return local.year.toString().padStart(4, '0') + "-" + two(local.monthValue) + "-" + two(local.dayOfMonth) + " " +
        two(local.hour) + ":" + two(local.minute)
}

/** Stable, language-neutral name of a failure kind for a bug report (`API`, `NETWORK`, ...), or "brak". */
fun diagnosticsErrorKind(kind: SyncErrorKind?): String = kind?.name ?: Texts.DIAGNOSTICS_NONE

/**
 * The whole report as plain text (PRD F46, docs/SPEC.md §47): app version, per-step times of the last
 * synchronization, the moment Toyota last saw the car, the last error (time, kind, HTTP status, sanitized
 * message) and the archive counters. No VIN, no coordinates, no token.
 */
fun diagnosticsReport(
    status: SyncStatus,
    appVersion: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val state = status.state
    val lines = mutableListOf<String>()
    lines += "Toyota Companion $appVersion"
    lines += Texts.DIAGNOSTICS_TITLE
    lines += "${Texts.DIAGNOSTICS_ATTEMPT}: ${diagnosticsTimestamp(state.lastAttemptAt, zone)}"
    lines += "${Texts.DIAGNOSTICS_SUCCESS}: ${diagnosticsTimestamp(state.lastSuccessAt, zone)}"
    lines += "${Texts.DIAGNOSTICS_VEHICLES}: ${diagnosticsTimestamp(state.lastVehiclesSuccessAt, zone)}"
    lines += "${Texts.DIAGNOSTICS_TELEMETRY}: ${diagnosticsTimestamp(state.lastTelemetrySuccessAt, zone)}"
    lines += "${Texts.DIAGNOSTICS_TRIPS}: ${diagnosticsTimestamp(state.lastTripsSuccessAt, zone)}"
    lines += "${Texts.DIAGNOSTICS_CAR_STATE}: ${diagnosticsTimestamp(status.carStateAt, zone)}"
    lines += if (state.lastErrorKind == null) {
        "${Texts.DIAGNOSTICS_ERROR}: ${Texts.DIAGNOSTICS_NONE}"
    } else {
        val at = diagnosticsTimestamp(state.lastErrorAt, zone)
        val code = state.lastErrorStatus?.let { " · HTTP $it" }.orEmpty()
        "${Texts.DIAGNOSTICS_ERROR}: $at · ${diagnosticsErrorKind(state.lastErrorKind)}$code"
    }
    state.lastErrorMessage?.let {
        // Sanitized when stored; a second pass keeps a report from an old row honest (docs/SECURITY.md).
        lines += "${Texts.DIAGNOSTICS_MESSAGE}: ${ToyotaSecrets.sanitize(it)}"
    }
    lines += Texts.diagnosticsArchive(status.tripsStored, status.readingsStored, status.pendingRefuels)
    return lines.joinToString("\n")
}
