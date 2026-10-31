package pl.lk.toyotacompanion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.ZoneId
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
 * @param prompt state holder of the "price right after a refuel" question
 * (docs/SPEC.md §13.3)
 * @param deepLinkRefuelId refuel id carried by the notification that was tapped,
 * null most of the time (docs/SPEC.md §13.1)
 * @param onDeepLinkShown the id was used; forgetting it keeps a rotation from
 * reopening the form the user has already left
 */
@Composable
fun AppRoot(
    session: SessionController,
    loadTrips: suspend () -> List<StoredTrip>,
    refuel: RefuelController,
    defaultPrice: DefaultPriceController,
    prompt: RefuelPromptController,
    backup: BackupController,
    refuelLog: RefuelLogController,
    askNotificationPermission: suspend () -> Boolean,
    deepLinkRefuelId: String? = null,
    onDeepLinkShown: () -> Unit = {},
) {
    val state by session.state.collectAsState()
    val priceState by defaultPrice.state.collectAsState()
    val promptState by prompt.state.collectAsState()
    val backupState by backup.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.Status) }
    // Which entry the price form edits; an id is not a secret (docs/SECURITY.md).
    var editedRefuelId by rememberSaveable { mutableStateOf<String?>(null) }
    // Whether the price form was reached from a notification: the keyboard opens there
    // only, not when the user browsed to the form from the list (docs/SPEC.md §13.1).
    // `remember`, not `rememberSaveable`: the keyboard is asked for once per tap, so a
    // rotation does not pull the cursor back to the price while the user types liters.
    var formFromNotification by remember { mutableStateOf(false) }

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
            prompt.reset()
            backup.reset()
            refuelLog.reset()
        } else {
            // The status button shows the stored price. This effect also restarts when the
            // Activity is recreated, so the read is guarded: the file is touched only while
            // the price is still unknown, never again on a rotation (docs/SPEC.md §12.3).
            defaultPrice.ensureLoaded()
            prompt.ensureLoaded()
            backup.ensureLoaded()
        }
    }

    // The prompt must not interrupt a form or the refuel list, so the controller is
    // told what is on screen — before any effect of this composition runs, so its
    // first decision already sees the real screen (docs/SPEC.md §13.3). The lambda
    // reads the state, not a copy of it.
    SideEffect { prompt.onFormScreen = { screen in PROMPT_BLOCKED_SCREENS } }
    // A new screen decides again from the last read; no database access, so a
    // rotation costs nothing (docs/SPEC.md §13.3).
    LaunchedEffect(screen) { prompt.screenChanged() }

    // A tapped notification opens the full price form of its entry (docs/SPEC.md §13.1).
    // `loggedIn` is a key: on a cold start the session is still loading when the id
    // arrives, and the form opens as soon as the stored login is read.
    LaunchedEffect(deepLinkRefuelId, loggedIn) {
        if (deepLinkRefuelId != null && loggedIn) {
            prompt.openFromNotification(deepLinkRefuelId)
            onDeepLinkShown()
        }
    }
    LaunchedEffect(promptState.formRequest) {
        val wanted = promptState.formRequest
        if (wanted != null) {
            editedRefuelId = wanted.id
            formFromNotification = true
            screen = Screen.RefuelForm
            prompt.formRequestHandled()
        }
    }

    ToyotaTheme {
        when (val current = state) {
            is UiState.Loading -> LoginScreen(UiState.LoggedOut(busy = true), onSubmit = { _, _ -> })

            is UiState.LoggedOut -> LoginScreen(current, onSubmit = session::login)

            is UiState.LoggedIn -> {
                // The question that follows a detected refuel, unless a refuel form is open
                // already (docs/SPEC.md §13.3).
                val shown = promptState.entry
                if (shown != null && screen !in PROMPT_BLOCKED_SCREENS) {
                    RefuelPromptDialog(
                        entry = shown,
                        priceText = promptState.priceText,
                        error = promptState.error,
                        busy = promptState.busy,
                        onPriceChange = prompt::priceChanged,
                        onSave = prompt::save,
                        onLater = prompt::later,
                        onMore = prompt::more,
                    )
                }
                when (screen) {
                    Screen.Status -> StatusScreen(
                        state = current,
                        onSyncNow = session::syncNow,
                        onRefresh = session::reload,
                        onShowTrips = { screen = Screen.Trips },
                        onShowRefuels = { screen = Screen.Refuels },
                        // Read in the navigation callback, so a rotation does not read again (§15.2).
                        onShowRefuelLog = {
                            refuelLog.load()
                            screen = Screen.RefuelLog
                        },
                        defaultPrice = priceState.price,
                        onShowDefaultPrice = { screen = Screen.DefaultPrice },
                        // Until the date is read there is no reminder: an unknown date is not "never".
                        backupReminder = if (backupState.loaded) {
                            backupReminder(backupState.lastExport, Instant.now(), current.status?.tripsStored ?: 0)
                        } else {
                            BackupReminder.None
                        },
                        lastBackupLine = backupState.lastExport
                            ?.let { Texts.lastBackup(backupDate(it, ZoneId.systemDefault())) }
                            ?: Texts.BACKUP_NONE_YET,
                        onShowBackup = { screen = Screen.Backup },
                        onLogout = session::logout,
                        askNotificationPermission = askNotificationPermission,
                    )

                    Screen.RefuelLog -> RefuelLogScreen(
                        controller = refuelLog,
                        onBack = { screen = Screen.Status },
                    )

                    // Leaving refreshes the counters: an import may have added trips (docs/SPEC.md §14.2).
                    Screen.Backup -> BackupScreen(
                        controller = backup,
                        hasTrips = (current.status?.tripsStored ?: 0) > 0,
                        onBack = {
                            screen = Screen.Status
                            session.reload()
                        },
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
                                focusPriceOnOpen = formFromNotification,
                                onBackToList = {
                                    formFromNotification = false
                                    screen = Screen.Refuels
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}