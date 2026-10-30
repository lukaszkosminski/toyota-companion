package pl.lk.toyotacompanion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.ui.theme.ToyotaTheme

/**
 * Root of the single-activity UI: one screen at a time, picked by the state the
 * [SessionController] publishes and — once logged in — by [Screen]
 * (docs/SPEC.md §9, §10.3). Every screen is wrapped in [ToyotaTheme]
 * (Material 3, light/dark, dynamic color on Android 12+).
 *
 * @param loadTrips reads the archived trips for the trips screen (docs/SPEC.md §10.1)
 */
@Composable
fun AppRoot(session: SessionController, loadTrips: suspend () -> List<StoredTrip>) {
    val state by session.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.Status) }

    val loggedIn = state is UiState.LoggedIn
    // Logged out: the login form is shown and the screen goes back to the status,
    // so the next login never lands on the trip list. Reset outside the composable
    // body, otherwise the assignment would repeat on every recomposition.
    LaunchedEffect(loggedIn) {
        if (!loggedIn) {
            screen = Screen.Status
        }
    }

    ToyotaTheme {
        when (val current = state) {
            is UiState.Loading -> LoginScreen(UiState.LoggedOut(busy = true), onSubmit = { _, _ -> })

            is UiState.LoggedOut -> LoginScreen(current, onSubmit = session::login)

            is UiState.LoggedIn ->
                if (screen == Screen.Trips) {
                    TripsScreen(onBack = { screen = Screen.Status }, loadTrips = loadTrips)
                } else {
                    StatusScreen(
                        state = current,
                        onSyncNow = session::syncNow,
                        onRefresh = session::reload,
                        onShowTrips = { screen = Screen.Trips },
                        onLogout = session::logout,
                    )
                }
        }
    }
}
