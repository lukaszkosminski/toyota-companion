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
 * Which of the two logged-in screens [AppRoot] shows (docs/SPEC.md §10.3).
 * Saved with `rememberSaveable`: a screen rotation keeps the list open, and it is
 * not a secret (docs/SECURITY.md). Logging out always returns to the login form
 * and resets it to [Status].
 */
enum class Screen {
    Status,
    Trips,
}
