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
 * (docs/SPEC.md §9, §10.3, §11.3). Every screen is wrapped in [ToyotaTheme]
 * (Material 3, light/dark, dynamic color on Android 12+).
 *
 * @param loadTrips reads the archived trips for the trips screen (docs/SPEC.md §10.1)
 * @param refuel state holder of the refuel list and the price form (docs/SPEC.md §11.3);
 * it belongs to the application scope, so a write survives these screens
 * @param defaultPrice state holder of the default fuel price (docs/SPEC.md §12.3); it
 * also belongs to the application scope, so storing a price survives this screen
 */
@Composable
fun AppRoot(
    session: SessionController,
    loadTrips: suspend () -> List<StoredTrip>,
    refuel: RefuelController,
    defaultPrice: DefaultPriceController,
) {
    val state by session.state.collectAsState()
    val priceState by defaultPrice.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.Status) }
    // Which entry the price form edits; an id is not a secret (docs/SECURITY.md).
    var editedRefuelId by rememberSaveable { mutableStateOf<String?>(null) }

    val loggedIn = state is UiState.LoggedIn
    // Logged out: the login form is shown, the screen goes back to the status and the
    // refuel state of the old account is forgotten, so the next login never lands on
    // a stale list (docs/SPEC.md §11.3). Reset outside the composable body, otherwise
    // the assignment would repeat on every recomposition.
    LaunchedEffect(loggedIn) {
        if (!loggedIn) {
            screen = Screen.Status
            editedRefuelId = null
            refuel.reset()
            defaultPrice.reset()
        } else {
            // The status button shows the stored price. This effect also restarts when the
            // Activity is recreated, so the read is guarded: the file is touched only while
            // the price is still unknown, never again on a rotation (docs/SPEC.md §12.3).
            defaultPrice.ensureLoaded()
        }
    }

    ToyotaTheme {
        when (val current = state) {
            is UiState.Loading -> LoginScreen(UiState.LoggedOut(busy = true), onSubmit = { _, _ -> })

            is UiState.LoggedOut -> LoginScreen(current, onSubmit = session::login)

            is UiState.LoggedIn -> when (screen) {
                Screen.Status -> StatusScreen(
                    state = current,
                    onSyncNow = session::syncNow,
                    onRefresh = session::reload,
                    onShowTrips = { screen = Screen.Trips },
                    onShowRefuels = { screen = Screen.Refuels },
                    defaultPrice = priceState.price,
                    onShowDefaultPrice = { screen = Screen.DefaultPrice },
                    onLogout = session::logout,
                )

                Screen.Trips -> TripsScreen(onBack = { screen = Screen.Status }, loadTrips = loadTrips)

                // Leaving for the status screen refreshes its counters — in the navigation
                // callback, so a rotation does not read the database for nothing (§11.3).
                Screen.Refuels -> RefuelListScreen(
                    controller = refuel,
                    onBack = {
                        screen = Screen.Status
                        session.reload()
                    },
                    onEdit = { id ->
                        editedRefuelId = id
                        screen = Screen.RefuelForm
                    },
                )

                Screen.DefaultPrice -> DefaultPriceScreen(
                    controller = defaultPrice,
                    onBackToStatus = {
                        screen = Screen.Status
                        session.reload()
                    },
                )

                Screen.RefuelForm -> {
                    val id = editedRefuelId
                    if (id == null) {
                        // Not reachable through the UI; fall back to the list anyway.
                        LaunchedEffect(Unit) { screen = Screen.Refuels }
                    } else {
                        RefuelFormScreen(
                            controller = refuel,
                            id = id,
                            onBackToList = { screen = Screen.Refuels },
                        )
                    }
                }
            }
        }
    }
}
