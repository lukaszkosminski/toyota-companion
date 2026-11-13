package pl.lk.toyotacompanion.ui

import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.sync.LoginFailure

/**
 * What the login form shows under the fields (docs/SPEC.md §9.2). The text comes
 * from [Texts]; the state itself carries no secret (docs/SECURITY.md).
 */
enum class LoginError {
    /** Empty login or password — checked locally, Toyota is not asked. */
    EMPTY_FIELDS,

    INVALID_CREDENTIALS,

    NETWORK,

    SERVICE,
}

fun LoginFailure.toLoginError(): LoginError = when (this) {
    LoginFailure.INVALID_CREDENTIALS -> LoginError.INVALID_CREDENTIALS
    LoginFailure.NETWORK -> LoginError.NETWORK
    LoginFailure.SERVICE -> LoginError.SERVICE
}

/**
 * Screen state of the single-activity app (docs/SPEC.md §9). No ViewModel:
 * `lifecycle-viewmodel-compose` is not a dependency of this module, so
 * [SessionController] holds the state instead.
 *
 * No state and no field of any state holds the password — [SessionController]
 * passes it straight to the verifier and forgets it.
 */
sealed class UiState {
    /** Credentials are being read from the Keystore (first frame only). */
    data object Loading : UiState()

    /** Login form. [busy] disables the button while Toyota is being asked. */
    data class LoggedOut(val error: LoginError? = null, val busy: Boolean = false) : UiState()

    /**
     * Synchronization status.
     *
     * @param status last read of the local archive, null until the first read
     * @param syncing "Synchronizuj teraz" in progress
     * @param lastSyncFailed kind of the failure of the last in-process sync
     */
    data class LoggedIn(
        val status: SyncStatus? = null,
        val syncing: Boolean = false,
        val lastSyncFailed: SyncErrorKind? = null,
    ) : UiState()
}

/**
 * The synchronization error the status screen shows (docs/SPEC.md §9.3): the failure of the last run in
 * this process ([UiState.LoggedIn.lastSyncFailed]) wins; otherwise the kind stored by the last
 * synchronization — including a background `SyncWorker` — but only while that error is newer than the last
 * full success, so an old error cannot hide a fresh success. No error, or no status read yet, gives null.
 */
fun visibleSyncError(status: SyncStatus?, lastSyncFailed: SyncErrorKind?): SyncErrorKind? {
    if (lastSyncFailed != null) return lastSyncFailed
    val state = status?.state ?: return null
    val kind = state.lastErrorKind ?: return null
    val errorAt = state.lastErrorAt ?: return null
    val successAt = state.lastSuccessAt
    // No success yet: the error is the newest thing that happened, so it is shown.
    return if (successAt == null || errorAt.isAfter(successAt)) kind else null
}

/**
 * Which logged-in screen [AppRoot] shows (docs/SPEC.md §10.3, §11.3).
 * Saved with `rememberSaveable`: a screen rotation keeps the screen open, and it is
 * not a secret (docs/SECURITY.md). Logging out always returns to the login form
 * and resets it to [Status].
 */
enum class Screen {
    Status,
    Trips,
    Refuels,
    RefuelForm,

    /** Default fuel price of the phone (docs/SPEC.md §12.3). */
    DefaultPrice,

    /** Export and import of the whole archive (docs/SPEC.md §14). */
    Backup,

    /** Confirmed refuels and the "computer vs actual" fuel economy (docs/SPEC.md §15). */
    RefuelLog,

    /** Electric share, years and seasons (docs/SPEC.md §16-§17). */
    Stats,

    /** Places and recent stops of the car (docs/SPEC.md §18). */
    Parking,

    /** Doors, windows and the warning count from Toyota's cloud (PRD F41, docs/SPEC.md §42), with the vehicle health card (PRD F43, §44). */
    VehicleStatus,

    /** The last known position of the car, kept in memory only (PRD F42, docs/SPEC.md §43). */
    Location,

    /** The service history Toyota keeps for the car (PRD F44, docs/SPEC.md §45). */
    ServiceHistory,

    /** The notification history of the account (PRD F45, docs/SPEC.md §46). */
    Notifications,

    /** Sync diagnostics: last successful steps, the last error and the archive counters (PRD F46, docs/SPEC.md §47). */
    Diagnostics,

    /** Eco score, repeated routes and fuel anomalies (docs/SPEC.md §19). */
    DrivingStyle,

    /** Trips by temperature and rain of their day (docs/SPEC.md §23). */
    Weather,

    /** The consumption heat map of one repeated route (docs/SPEC.md §34). */
    Heat,

    /** One year of driving on one page (docs/SPEC.md §29). */
    Year,

    /** The map of a trip or a parking place, shown in the app (docs/SPEC.md §26). */
    Map,
    /** Expenses other than fuel: service, inspection, tyres... */
    Expenses,

    /** App options: the language. */
    Options,
}
