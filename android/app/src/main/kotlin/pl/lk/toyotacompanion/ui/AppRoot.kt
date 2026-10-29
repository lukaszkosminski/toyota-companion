package pl.lk.toyotacompanion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Root of the single-activity UI: the login form or the synchronization status,
 * chosen by [SessionController] (docs/SPEC.md §9). No navigation library.
 */
@Composable
fun AppRoot(session: SessionController) {
    val state by session.state.collectAsState()
    MaterialTheme {
        when (val screen = state) {
            // The first frame while the Keystore read is in flight: a form that cannot be submitted.
            is UiState.Loading -> LoginScreen(UiState.LoggedOut(busy = true), onLogin = session::login)
            is UiState.LoggedOut -> LoginScreen(screen, onLogin = session::login)
            is UiState.LoggedIn -> StatusScreen(
                state = screen,
                onSyncNow = session::syncNow,
                onLogout = session::logout,
            )
        }
    }
}
