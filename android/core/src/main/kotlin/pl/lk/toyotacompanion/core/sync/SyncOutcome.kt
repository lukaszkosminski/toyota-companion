package pl.lk.toyotacompanion.core.sync

import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState

/** No stored Toyota credentials — nothing to log in with (docs/SECURITY.md). */
class NoCredentialsException(message: String = "No stored Toyota credentials") : Exception(message)

/**
 * Kind + human-readable reason of a failed synchronization. [message] is always
 * sanitized with `ToyotaSecrets.sanitize`: never a password, token or VIN
 * (docs/SECURITY.md).
 */
data class SyncError(val kind: SyncErrorKind, val message: String) {
    /** Whether trying again later can help (429/5xx/network) — drives `Result.retry()` in the worker. */
    val retryable: Boolean get() = kind.retryable
}

/**
 * Result of one synchronization. Outcomes, never exceptions
 * (docs/SPEC.md §8.5): the caller in the app module maps them directly onto
 * `WorkResult` and onto the “data shown but not current” banner.
 */
sealed class SyncOutcome {
    /**
     * @param telemetryFailed telemetry was unusable or its request failed; trips
     * were still fetched and stored, so the archive is current.
     */
    data class Ok(
        val tripsAdded: Int,
        val readingsAdded: Int,
        val refuelsDetected: Int,
        val tripsCosted: Int,
        val telemetryFailed: Boolean,
        val state: SyncState,
    ) : SyncOutcome()

    /**
     * The run stopped at [error]. Stored data is never removed or rewound; steps
     * completed before the failure (vehicles, a telemetry reading, a detected
     * refuel) stay saved, but the trip watermark does not advance.
     */
    data class Failed(val error: SyncError, val state: SyncState) : SyncOutcome()

    /** Another synchronization is in progress (single flight). */
    data object Skipped : SyncOutcome()
}
