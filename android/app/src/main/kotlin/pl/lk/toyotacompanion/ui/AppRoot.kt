package pl.lk.toyotacompanion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import pl.lk.toyotacompanion.ui.theme.ToyotaTheme

/**
 * Root of the single-activity UI: one screen at a time, picked by the state the
 * [SessionController] publishes. Every screen is wrapped in [ToyotaTheme]
 * (Material 3, light/dark, dynamic color on Android 12+).
 */
@Composable
fun AppRoot(session: SessionController) {
    val state by session.state.collectAsState()
    ToyotaTheme {
        when (val current = state) {
            is UiState.Loading -> LoginScreen(UiState.LoggedOut(busy = true), onSubmit = { _, _ -> })
            is UiState.LoggedOut -> LoginScreen(current, onSubmit = session::login)
            is UiState.LoggedIn -> StatusScreen(
                state = current,
                onSyncNow = session::syncNow,
                onRefresh = session::reload,
                onLogout = session::logout,
            )
        }
    }
}
