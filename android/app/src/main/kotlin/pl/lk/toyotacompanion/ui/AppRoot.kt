package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    receipts: pl.lk.toyotacompanion.receipts.ReceiptRepository,
    stats: StatsController,
    parking: ParkingController,
    drivingStyle: DrivingStyleController,
    weather: WeatherController,
    forecast: ForecastController,
    consumption: ConsumptionController,
    stationPrices: StationPriceController,
    tripRoute: TripRouteController,
    yearSummary: YearSummaryController,
    declared: DeclaredConsumptionController,
    ecoGoals: EcoGoalsController,
    budget: BudgetController,
    periodSummary: PeriodSummarySettings,
    tyres: TyreController,
    carValuation: CarValuationController,
    backfill: RouteBackfillController,
    heat: RouteHeatController,
    expenses: ExpensesController,
    vehicleStatus: RemoteDataController<pl.lk.toyotacompanion.core.domain.VehicleStatus>,
    location: RemoteDataController<pl.lk.toyotacompanion.core.domain.VehicleLocation>,
    vehicleHealth: RemoteDataController<pl.lk.toyotacompanion.core.domain.VehicleHealth>,
    serviceHistory: RemoteDataController<pl.lk.toyotacompanion.core.domain.ServiceHistory>,
    notifications: RemoteDataController<List<pl.lk.toyotacompanion.core.domain.ToyotaNotification>>,
    diagnostics: RemoteDataController<pl.lk.toyotacompanion.core.data.SyncStatus>,
    appVersion: String,
    update: UpdateController,
    onSetLanguage: (AppLanguage) -> Unit,
    askNotificationPermission: suspend () -> Boolean,
    deepLinkRefuelId: String? = null,
    onDeepLinkShown: () -> Unit = {},
) {
    val state by session.state.collectAsState()
    val priceState by defaultPrice.state.collectAsState()
    val promptState by prompt.state.collectAsState()
    val backupState by backup.state.collectAsState()
    val forecastState by forecast.state.collectAsState()
    val consumptionState by consumption.state.collectAsState()
    val routeState by tripRoute.state.collectAsState()
    val widgetContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val declaredState by declared.state.collectAsState()
    val ecoGoalsState by ecoGoals.state.collectAsState()
    val budgetState by budget.state.collectAsState()
    val periodSummaryState by periodSummary.state.collectAsState()
    val carValuationState by carValuation.state.collectAsState()
    val heatState by heat.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.Status) }
    // The status screen is left for every tile and left in composition each time, so its scroll position lives here: coming back from
    // a sub-page lands on the tile that was tapped. A logout bumps the epoch, so the next login starts at the top.
    var statusScrollEpoch by remember { mutableIntStateOf(0) }
    val statusScroll = rememberSaveable(statusScrollEpoch, saver = ScrollState.Saver) { ScrollState(0) }
    // The map in the app (docs/SPEC.md §26): [origin ordinal, then per point: label index, lat, lon] with the positions
    // already rounded, so no raw coordinate lands in the saved state.
    var mapData by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    // The exact position of the car (docs/SPEC.md §43, ADR-022) is never saved: it lives in this plain `remember` state only, so a
    // process death forgets it (the map then falls back to the status screen) and no exact coordinate reaches the saved state.
    var exactMap by remember { mutableStateOf<Pair<Screen, List<MapPoint>>?>(null) }
    fun showMap(origin: Screen, points: List<MapPoint>, tripId: String?, exact: Boolean = false) {
        tripRoute.open(tripId)
        if (exact) {
            mapData = null
            exactMap = origin to points
        } else {
            exactMap = null
            mapData = doubleArrayOf(origin.ordinal.toDouble()) +
                points.flatMap { listOf(MAP_LABELS.indexOf(it.label).toDouble(), it.lat, it.lon) }
        }
        screen = Screen.Map
    }
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
            statusScrollEpoch++
            editedRefuelId = null
            refuel.reset()
            defaultPrice.reset()
            prompt.reset()
            backup.reset()
            refuelLog.reset()
            stats.reset()
            parking.reset()
            drivingStyle.reset()
            weather.reset()
            forecast.reset()
            consumption.reset()
            ecoGoals.reset()
            budget.reset()
            carValuation.reset()
            stationPrices.reset()
            tripRoute.close()
            yearSummary.reset()
            expenses.reset()
            tyres.reset()
            exactMap = null
            vehicleStatus.reset()
            location.reset()
            vehicleHealth.reset()
            serviceHistory.reset()
            notifications.reset()
            diagnostics.reset()
            backfill.reset()
            heat.close()
            // The widget must not keep an account's numbers after logout (docs/SPEC.md §32).
            pl.lk.toyotacompanion.widget.WidgetSnapshotStore(widgetContext).clear()
            pl.lk.toyotacompanion.widget.ConsumptionWidgetProvider.refresh(widgetContext)
        } else {
            // The status button shows the stored price. This effect also restarts when the
            // Activity is recreated, so the read is guarded: the file is touched only while
            // the price is still unknown, never again on a rotation (docs/SPEC.md §12.3).
            defaultPrice.ensureLoaded()
            prompt.ensureLoaded()
            backup.ensureLoaded()
            forecast.ensureLoaded()
            consumption.ensureLoaded()
            declared.ensureLoaded()
            ecoGoals.ensureLoaded()
            budget.ensureLoaded()
        }
    }

    // The prompt must not interrupt a form or the refuel list, so the controller is
    // told what is on screen — before any effect of this composition runs, so its
    // first decision already sees the real screen (docs/SPEC.md §13.3). The lambda
    // reads the state, not a copy of it.
    SideEffect { prompt.onFormScreen = { screen in PROMPT_BLOCKED_SCREENS } }
    // A new screen decides again from the last read; no database access, so a
    // rotation costs nothing (docs/SPEC.md §13.3). The status screen refreshes the budget on every
    // entry, so a new expense or trip shows up without a restart (PRD F48, docs/SPEC.md §49).
    LaunchedEffect(screen) {
        prompt.screenChanged()
        if (screen == Screen.Status) budget.refresh()
    }

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
                        stationText = promptState.stationText,
                        stationSuggestions = promptState.stationSuggestions,
                        error = promptState.error,
                        busy = promptState.busy,
                        onPriceChange = prompt::priceChanged,
                        onStationChange = prompt::stationChanged,
                        onSave = prompt::save,
                        onLater = prompt::later,
                        onMore = prompt::more,
                    )
                }
                when (screen) {
                    Screen.Status -> StatusScreen(
                        state = current,
                        scrollState = statusScroll,
                        // The tile shows the fuel now: the newest reading adjusted by the trips since then (§20.2).
                        fuelLevelPct = forecastState?.forecast?.levelPct?.toInt(),
                        onSyncNow = session::syncNow,
                        onShowTrips = { screen = Screen.Trips },
                        onShowRefuels = { screen = Screen.Refuels },
                        // Read in the navigation callback, so a rotation does not read again (§15.2).
                        onShowRefuelLog = {
                            refuelLog.load()
                            screen = Screen.RefuelLog
                        },
                        onShowStats = {
                            stats.load()
                            screen = Screen.Stats
                        },
                        onShowParking = {
                            parking.load()
                            screen = Screen.Parking
                        },
                        onShowVehicleStatus = { screen = Screen.VehicleStatus },
                        onShowLocation = { screen = Screen.Location },
                        onShowServiceHistory = { screen = Screen.ServiceHistory },
                        onShowNotifications = { screen = Screen.Notifications },
                        onShowDiagnostics = { screen = Screen.Diagnostics },
                        onShowDrivingStyle = {
                            drivingStyle.load()
                            screen = Screen.DrivingStyle
                        },
                        onShowYear = {
                            yearSummary.load()
                            screen = Screen.Year
                        },
                        onShowWeather = {
                            weather.load()
                            screen = Screen.Weather
                        },
                        onShowExpenses = {
                            expenses.load()
                            screen = Screen.Expenses
                        },
                        onShowOptions = {
                            carValuation.open()
                            screen = Screen.Options
                        },
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
                        forecastLine = forecastLine(forecastState, ZoneId.systemDefault()),
                        consumption = consumptionState,
                        declaredConsumption = declaredState,
                        onSetDeclared = declared::set,
                        ecoGoals = ecoGoalsState,
                        onSetEcoGoals = ecoGoals::set,
                        budget = budgetState,
                        onSetBudget = budget::set,
                        onLogout = session::logout,
                        askNotificationPermission = askNotificationPermission,
                    )

                    Screen.Options -> OptionsScreen(
                        update = update,
                        onSetLanguage = onSetLanguage,
                        defaultPrice = priceState.price,
                        onShowDefaultPrice = { screen = Screen.DefaultPrice },
                        budgetAlert = budgetState.alertEnabled,
                        onSetBudgetAlert = budget::setAlert,
                        periodSummary = periodSummaryState,
                        onSetPeriodSummary = periodSummary::set,
                        carValuation = carValuationState,
                        onSaveCarValuation = carValuation::save,
                        onRemoveCarValuation = carValuation::remove,
                        onBack = { screen = Screen.Status },
                    )
                    Screen.Expenses -> ExpensesScreen(
                        receipts = receipts,
                        tyres = tyres,
                        controller = expenses,
                        fuelTotal = consumptionState?.totalCost,
                        fuelByMonth = consumptionState?.monthCosts.orEmpty()
                            .mapNotNull { m -> m.costPln?.let { m.month to it } }.toMap(),
                        onBack = { screen = Screen.Status },
                    )
                    Screen.Year -> YearSummaryScreen(controller = yearSummary, onBack = { screen = Screen.Status })
                    Screen.Weather -> WeatherScreen(controller = weather, onBack = { screen = Screen.Status })
                    Screen.Heat -> MapScreen(
                        title = Texts.HEAT_TITLE,
                        points = (heatState as? HeatState.Ready)?.points.orEmpty(),
                        route = RouteState.None,
                        heat = heatState,
                        onBack = {
                            heat.close()
                            screen = Screen.DrivingStyle
                        },
                    )
                    Screen.DrivingStyle -> DrivingStyleScreen(
                        controller = drivingStyle,
                        onBack = { screen = Screen.Status },
                        onShowHeat = { ids ->
                            heat.open(ids)
                            screen = Screen.Heat
                        },
                    )

                    Screen.Parking -> ParkingScreen(
                        onShowMap = { points, id -> showMap(Screen.Parking, points, id) },
                        controller = parking,
                        onBack = { screen = Screen.Status },
                    )

                    Screen.VehicleStatus -> VehicleStatusScreen(
                        controller = vehicleStatus,
                        healthController = vehicleHealth,
                        onBack = { screen = Screen.Status },
                    )

                    Screen.Location -> LocationScreen(
                        controller = location,
                        // The map opens only after a tap, with this one exact point (docs/SECURITY.md).
                        onShowMap = { points -> showMap(Screen.Location, points, null, exact = true) },
                        // Home and work from the archive, only to name the position; computed in memory (ADR-022).
                        findPlaces = {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                knownPlaces(parkingLogOf(loadTrips()), ZoneId.systemDefault())
                            }
                        },
                        onBack = { screen = Screen.Status },
                    )

                    Screen.ServiceHistory -> ServiceHistoryScreen(
                        controller = serviceHistory,
                        onBack = { screen = Screen.Status },
                    )

                    Screen.Notifications -> NotificationsScreen(
                        controller = notifications,
                        onBack = { screen = Screen.Status },
                    )

                    Screen.Diagnostics -> DiagnosticsScreen(
                        controller = diagnostics,
                        appVersion = appVersion,
                        onBack = { screen = Screen.Status },
                    )

                    Screen.Stats -> StatsScreen(
                        controller = stats,
                        onBack = { screen = Screen.Status },
                    )

                    Screen.RefuelLog -> RefuelLogScreen(
                        controller = refuelLog,
                        receipts = receipts,
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

                    Screen.Trips -> TripsScreen(
                        onBack = { screen = Screen.Status },
                        onShowMap = { points, id -> showMap(Screen.Trips, points, id) },
                        weather = weather,
                        backfill = backfill,
                        loadTrips = loadTrips,
                    )

                    Screen.Map -> {
                        val data = mapData
                        val exact = exactMap
                        val shown: Pair<Screen, List<MapPoint>>? = when {
                            exact != null -> exact
                            data != null && data.size >= 4 -> Screen.entries[data[0].toInt()] to (1 until data.size step 3).mapNotNull { i ->
                                val label = MAP_LABELS.getOrNull(data[i].toInt()) ?: return@mapNotNull null
                                if (i + 2 < data.size) MapPoint(label, data[i + 1], data[i + 2]) else null
                            }
                            else -> null
                        }
                        if (shown == null) {
                            LaunchedEffect(Unit) { screen = Screen.Status }
                        } else {
                            val (origin, points) = shown
                            MapScreen(
                                title = if (points.size == 1) Texts.MAP_TITLE_PLACE else Texts.MAP_TITLE_TRIP,
                                points = points,
                                route = routeState,
                                exact = exact != null,
                                onBack = {
                                    tripRoute.close()
                                    exactMap = null
                                    screen = origin
                                },
                            )
                        }
                    }

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
                        prices = stationPrices,
                        // The price is set from "Opcje" (docs/SPEC.md §12.3), so back goes there; the status counters follow on leaving it.
                        onBack = {
                            screen = Screen.Options
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
                                prices = stationPrices,
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