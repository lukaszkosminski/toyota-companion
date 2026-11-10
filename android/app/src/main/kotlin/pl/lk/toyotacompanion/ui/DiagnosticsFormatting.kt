package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.LocalDate
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
 * message) and the archive counters. No VIN, no coordinates, no token: the stored message goes through
 * [ToyotaSecrets.sanitize] and every occurrence of the bare VIN through [ToyotaSecrets.maskVin].
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
    // A message without a kind is a half-written or old row; showing it under "no error" would confuse (D3).
    if (state.lastErrorKind != null) {
        state.lastErrorMessage?.let {
            // Sanitized when stored; a second pass keeps a report from an old row honest (docs/SECURITY.md).
            lines += "${Texts.DIAGNOSTICS_MESSAGE}: ${ToyotaSecrets.sanitize(it)}"
        }
    }
    lines += Texts.diagnosticsArchive(status.tripsStored, status.readingsStored, status.pendingRefuels)
    // sanitize only knows `key=value`; a bare VIN in a message needs its own pass (docs/SPEC.md §47).
    return ToyotaSecrets.maskVin(lines.joinToString("\n"), state.vin)
}

/**
 * The same facts as [diagnosticsReport], as label and value rows for the screen (C32 U20): the times in the short stamp of the rest of the
 * app (`10.11 19:04`, the year only when it is not [today]'s), no title repeated and the error on one row. The message goes through the same
 * two passes as the report, so no VIN, coordinate or token reaches the screen either.
 */
fun diagnosticsRows(
    status: SyncStatus,
    appVersion: String,
    zone: ZoneId,
    today: LocalDate,
): List<LabelValue> {
    fun stamp(at: Instant?): String = at?.let { TripsFormatting.shortStamp(it, zone, today) } ?: Texts.DIAGNOSTICS_NONE
    val state = status.state
    val rows = mutableListOf(
        LabelValue(Texts.DIAGNOSTICS_VERSION, appVersion),
        LabelValue(Texts.DIAGNOSTICS_ATTEMPT, stamp(state.lastAttemptAt)),
        LabelValue(Texts.DIAGNOSTICS_SUCCESS, stamp(state.lastSuccessAt)),
        LabelValue(Texts.DIAGNOSTICS_VEHICLES, stamp(state.lastVehiclesSuccessAt)),
        LabelValue(Texts.DIAGNOSTICS_TELEMETRY, stamp(state.lastTelemetrySuccessAt)),
        LabelValue(Texts.DIAGNOSTICS_TRIPS, stamp(state.lastTripsSuccessAt)),
        LabelValue(Texts.DIAGNOSTICS_CAR_STATE, stamp(status.carStateAt)),
    )
    if (state.lastErrorKind == null) {
        rows += LabelValue(Texts.DIAGNOSTICS_ERROR, Texts.DIAGNOSTICS_NONE)
    } else {
        val code = state.lastErrorStatus?.let { " · HTTP $it" }.orEmpty()
        rows += LabelValue(Texts.DIAGNOSTICS_ERROR, stamp(state.lastErrorAt) + Texts.SEPARATOR + diagnosticsErrorKind(state.lastErrorKind) + code)
        state.lastErrorMessage?.let {
            rows += LabelValue(Texts.DIAGNOSTICS_MESSAGE, ToyotaSecrets.maskVin(ToyotaSecrets.sanitize(it), state.vin))
        }
    }
    rows += LabelValue(Texts.DIAGNOSTICS_ARCHIVE_TRIPS, TripsFormatting.count(status.tripsStored))
    rows += LabelValue(Texts.DIAGNOSTICS_ARCHIVE_READINGS, TripsFormatting.count(status.readingsStored))
    rows += LabelValue(Texts.DIAGNOSTICS_ARCHIVE_PENDING, TripsFormatting.count(status.pendingRefuels))
    return rows
}
