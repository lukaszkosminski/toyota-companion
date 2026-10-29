package pl.lk.toyotacompanion.core.sync

import kotlinx.coroutines.CancellationException
import pl.lk.toyotacompanion.core.toyota.TokenInfo
import pl.lk.toyotacompanion.core.toyota.ToyotaLoginException
import pl.lk.toyotacompanion.core.toyota.ToyotaNetworkException

/** Why a login attempt failed; the UI turns these into Polish messages (docs/SPEC.md §9). */
enum class LoginFailure {
    /** Wrong login or password (also an unknown account) — the user has to correct the form. */
    INVALID_CREDENTIALS,

    /** No reachable network — retry after the connection is back, the form may stay as it is. */
    NETWORK,

    /** Toyota service problem (HTTP error, unexpected payload) — try again later. */
    SERVICE,
}

/**
 * Result of one login attempt checked against the real Toyota account
 * (docs/SPEC.md §9). Outcomes, never exceptions (same rule as `SyncOutcome`).
 *
 * [Ok] carries the freshly issued token so the caller can store it and skip a
 * second authentication; its `toString` never prints the token.
 */
sealed class LoginCheck {
    data class Ok(val token: TokenInfo) : LoginCheck() {
        override fun toString(): String = "LoginCheck.Ok(<redacted>)"
    }

    data class Failed(val reason: LoginFailure) : LoginCheck()
}

/**
 * Toyota exceptions -> [LoginFailure]. Anything that is not a login or a
 * transport problem is a service problem: a 403/5xx from the AMI endpoints must
 * not tell the user their password is wrong (docs/SPEC.md §9).
 */
fun classifyLoginFailure(e: Exception): LoginFailure {
    if (e is CancellationException) throw e
    return when (e) {
        is ToyotaLoginException -> LoginFailure.INVALID_CREDENTIALS
        is ToyotaNetworkException -> LoginFailure.NETWORK
        else -> LoginFailure.SERVICE
    }
}
