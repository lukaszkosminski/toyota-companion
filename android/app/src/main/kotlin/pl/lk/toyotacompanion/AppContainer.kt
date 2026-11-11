package pl.lk.toyotacompanion

import android.app.backup.BackupManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.WorkManager
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.ServiceHistory
import pl.lk.toyotacompanion.core.domain.ToyotaNotification
import pl.lk.toyotacompanion.core.domain.VehicleHealth
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus
import pl.lk.toyotacompanion.core.sync.NoCredentialsException
import pl.lk.toyotacompanion.core.sync.SyncEngine
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.core.sync.ToyotaGateway
import pl.lk.toyotacompanion.core.sync.ToyotaGatewayFactory
import pl.lk.toyotacompanion.core.toyota.TokenInfo
import pl.lk.toyotacompanion.core.toyota.TokenStore
import pl.lk.toyotacompanion.core.toyota.ToyotaController
import pl.lk.toyotacompanion.core.vehicle.VehicleDataGateway
import pl.lk.toyotacompanion.data.db.AppDatabase
import pl.lk.toyotacompanion.data.db.RoomCostRepository
import pl.lk.toyotacompanion.data.db.RoomFuelReadingRepository
import pl.lk.toyotacompanion.data.db.RoomRefuelRepository
import pl.lk.toyotacompanion.data.db.RoomSyncStateRepository
import pl.lk.toyotacompanion.data.db.RoomTripRepository
import pl.lk.toyotacompanion.data.db.RoomVehicleRepository
import pl.lk.toyotacompanion.notify.RefuelNotifier
import pl.lk.toyotacompanion.notify.notificationPermissionGranted
import pl.lk.toyotacompanion.notify.shouldAskNotificationPermission
import pl.lk.toyotacompanion.security.AndroidKeystoreCipher
import pl.lk.toyotacompanion.security.CredentialStore
import pl.lk.toyotacompanion.security.EncryptedSecretStore
import pl.lk.toyotacompanion.security.KeystoreTokenStore
import pl.lk.toyotacompanion.security.SecretCipher
import pl.lk.toyotacompanion.settings.SettingsStore
import pl.lk.toyotacompanion.sync.SyncRunner
import pl.lk.toyotacompanion.ui.ARCHIVE_IMPORT_LIMIT_BYTES
import pl.lk.toyotacompanion.ui.BackupController
import pl.lk.toyotacompanion.ui.CarValuationController
import pl.lk.toyotacompanion.ui.DefaultPriceController
import pl.lk.toyotacompanion.core.prices.FuelPriceGatewayFactory
import pl.lk.toyotacompanion.ui.ConsumptionController
import pl.lk.toyotacompanion.ui.DeclaredConsumptionController
import pl.lk.toyotacompanion.ui.EcoGoals
import pl.lk.toyotacompanion.ui.EcoGoalsController
import pl.lk.toyotacompanion.ui.StationPriceController
import pl.lk.toyotacompanion.ui.RouteBackfillController
import pl.lk.toyotacompanion.ui.RouteHeatController
import pl.lk.toyotacompanion.ui.TripRouteController
import pl.lk.toyotacompanion.ui.toExpense
import pl.lk.toyotacompanion.ui.YearSummaryController
import pl.lk.toyotacompanion.data.db.RoomTripRouteRepository
import pl.lk.toyotacompanion.ui.DrivingStyleController
import pl.lk.toyotacompanion.ui.WeatherController
import pl.lk.toyotacompanion.core.weather.WeatherGatewayFactory
import pl.lk.toyotacompanion.data.db.RoomWeatherRepository
import pl.lk.toyotacompanion.ui.ForecastController
import pl.lk.toyotacompanion.ui.ParkingController
import pl.lk.toyotacompanion.ui.RefuelController
import pl.lk.toyotacompanion.ui.RefuelLogController
import pl.lk.toyotacompanion.ui.RefuelPromptController
import pl.lk.toyotacompanion.ui.SessionController
import pl.lk.toyotacompanion.ui.StatsController
import pl.lk.toyotacompanion.ui.readArchiveBytes
import pl.lk.toyotacompanion.ui.toExpense

/** Hand-wired dependencies (no DI framework). Everything is lazy: nothing touches Keystore or disk at app start. */
class AppContainer(
    private val context: Context,
    cipherFactory: () -> SecretCipher = { AndroidKeystoreCipher() },
) {
    private val secretStore by lazy {
        EncryptedSecretStore(context.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE), cipherFactory())
    }
    /**
     * Phone settings such as the default fuel price. A separate, unencrypted file:
     * a fuel price is not a secret (docs/SPEC.md §12.2, docs/SECURITY.md).
     */
    private val settingsStore: SettingsStore by lazy {
        SettingsStore(context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE))
    }

    init {
        // One small preference read, so the very first frame already speaks the chosen language.
        pl.lk.toyotacompanion.ui.Lang.current = pl.lk.toyotacompanion.ui.AppLanguage.fromTag(settingsStore.language())
    }

    /** The language the texts are read in; applied before the first screen, kept in the settings file. */
    fun setLanguage(language: pl.lk.toyotacompanion.ui.AppLanguage) {
        pl.lk.toyotacompanion.ui.Lang.current = language
        uiScope.launch(Dispatchers.IO) { settingsStore.saveLanguage(language.tag) }
        pl.lk.toyotacompanion.widget.ConsumptionWidgetProvider.refresh(context)
    }

    val expensesController: pl.lk.toyotacompanion.ui.ExpensesController by lazy {
        pl.lk.toyotacompanion.ui.ExpensesController(
            database.expenseDao(),
            uiScope,
            Dispatchers.IO,
            // A new or removed expense changes the month's budget, so the thresholds are checked (PRD F49, §49).
            onChanged = { budgetAlertNotifier.onExpensesChanged() },
        )
    }

    /**
     * Read-only Toyota data outside the archive (PRD F41-F45, docs/SPEC.md §42-§46): vehicle status,
     * the last known position, vehicle health, the service history and the notifications. Each screen reads
     * once when it opens; the position is kept in memory only (docs/SECURITY.md). The shared controller renews
     * the token once for every call.
     */
    private val vehicleData: VehicleDataGateway = object : VehicleDataGateway {
        override suspend fun status(vin: String): VehicleStatus? =
            ToyotaGatewayFactory.vehicleData(sharedController()).status(vin)

        override suspend fun location(vin: String): VehicleLocation? =
            ToyotaGatewayFactory.vehicleData(sharedController()).location(vin)

        override suspend fun health(vin: String): VehicleHealth? =
            ToyotaGatewayFactory.vehicleData(sharedController()).health(vin)

        override suspend fun serviceHistory(vin: String): ServiceHistory? =
            ToyotaGatewayFactory.vehicleData(sharedController()).serviceHistory(vin)

        override suspend fun notifications(vin: String): List<ToyotaNotification>? =
            ToyotaGatewayFactory.vehicleData(sharedController()).notifications(vin)
    }

    private suspend fun selectedVin(): String? = syncEngine.selectedVin()

    val vehicleStatusController: pl.lk.toyotacompanion.ui.RemoteDataController<VehicleStatus> by lazy {
        pl.lk.toyotacompanion.ui.RemoteDataController(
            read = { selectedVin()?.let { vehicleData.status(it) } },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    val locationController: pl.lk.toyotacompanion.ui.RemoteDataController<VehicleLocation> by lazy {
        pl.lk.toyotacompanion.ui.RemoteDataController(
            read = { selectedVin()?.let { vehicleData.location(it) } },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    val vehicleHealthController: pl.lk.toyotacompanion.ui.RemoteDataController<VehicleHealth> by lazy {
        pl.lk.toyotacompanion.ui.RemoteDataController(
            read = { selectedVin()?.let { vehicleData.health(it) } },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** The service history Toyota keeps for the car (PRD F44, docs/SPEC.md §45); read on every opening. */
    val serviceHistoryController: pl.lk.toyotacompanion.ui.RemoteDataController<ServiceHistory> by lazy {
        pl.lk.toyotacompanion.ui.RemoteDataController(
            read = { selectedVin()?.let { vehicleData.serviceHistory(it) } },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** The notification history of the account (PRD F45, docs/SPEC.md §46); read on every opening. */
    val notificationsController: pl.lk.toyotacompanion.ui.RemoteDataController<List<ToyotaNotification>> by lazy {
        pl.lk.toyotacompanion.ui.RemoteDataController(
            read = { selectedVin()?.let { vehicleData.notifications(it) } },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /**
     * Sync diagnostics (PRD F46, docs/SPEC.md §47): a local read of the archive and the sync bookkeeping.
     * No Toyota call, so the car is never woken, and nothing is written.
     */
    val diagnosticsController: pl.lk.toyotacompanion.ui.RemoteDataController<SyncStatus> by lazy {
        pl.lk.toyotacompanion.ui.RemoteDataController(
            read = { syncEngine.status() },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** Version shown in the diagnostics report and compared with the GitHub release (ADR-016). */
    val appVersionName: String by lazy {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.takeIf { it.isNotBlank() } ?: "?"
    }

    /** App update from GitHub Releases, only after a tap in "Opcje" (PRD F36, docs/SPEC.md §37, ADR-016). */
    val updateController: pl.lk.toyotacompanion.ui.UpdateController by lazy {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        pl.lk.toyotacompanion.ui.UpdateController(
            gateway = pl.lk.toyotacompanion.core.update.AppUpdateGatewayFactory.create(),
            installer = pl.lk.toyotacompanion.update.AndroidApkInstaller(context),
            currentVersionCode = installed.longVersionCode,
            currentVersionName = installed.versionName.orEmpty(),
            apkFile = java.io.File(context.cacheDir, "updates/update.apk"),
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    val credentialStore: CredentialStore by lazy { CredentialStore(secretStore) }

    /**
     * The encrypted token file. Clearing the tokens (logout) also drops the shared [ToyotaController], so the access
     * token kept in its memory does not outlive the file it was read from (docs/SECURITY.md).
     */
    val tokenStore: TokenStore by lazy {
        val keystore = KeystoreTokenStore(secretStore)
        object : TokenStore {
            override fun load(username: String): TokenInfo? = keystore.load(username)
            override fun save(username: String, token: TokenInfo) = keystore.save(username, token)
            override fun clear(username: String) {
                keystore.clear(username)
                forgetSharedController()
            }
        }
    }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .setDriver(AndroidSQLiteDriver())
            .build()
    }

    /** The account the shared [ToyotaController] belongs to, and the controller itself. */
    private class AccountController(val username: String, val password: String, val controller: ToyotaController)

    private val controllerLock = Any()
    private var accountController: AccountController? = null

    /**
     * One [ToyotaController] for every Toyota call of the stored account – synchronization, a trip route and the
     * route backfill – so its lock renews the token once when they run in parallel (single flight). Rebuilt only when
     * the stored login or password changes; the login is read at call time, so a password change needs no restart.
     */
    private fun sharedController(): ToyotaController {
        val credentials = credentialStore.load() ?: throw NoCredentialsException()
        synchronized(controllerLock) {
            val cached = accountController
            if (cached != null && cached.username == credentials.username && cached.password == credentials.password) {
                return cached.controller
            }
            val controller = ToyotaGatewayFactory.controller(credentials.username, credentials.password, tokenStore)
            accountController = AccountController(credentials.username, credentials.password, controller)
            return controller
        }
    }

    /** Logout clears the token file; the controller's in-memory copy of the token goes with it (docs/SECURITY.md). */
    private fun forgetSharedController() {
        synchronized(controllerLock) { accountController = null }
    }

    private val gateway = object : ToyotaGateway {
        private fun current(): ToyotaGateway = ToyotaGatewayFactory.create(sharedController())

        override suspend fun vehicles(): List<VehicleRecord> = current().vehicles()

        override suspend fun telemetry(vin: String): FuelReading? = current().telemetry(vin)

        override suspend fun trips(vin: String, from: LocalDate, to: LocalDate): List<TripRecord> =
            current().trips(vin, from, to)
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            gateway = gateway,
            vehicles = RoomVehicleRepository(database.vehicleDao()),
            trips = RoomTripRepository(database.tripDao()),
            refuels = RoomRefuelRepository(database.refuelDao()),
            readings = RoomFuelReadingRepository(database.fuelReadingDao()),
            costs = RoomCostRepository(database.tripCostDao()),
            state = RoomSyncStateRepository(database.syncStateDao()),
            clock = { Instant.now() },
            hasCredentials = { credentialStore.load() != null },
            // Read per recompute, always from a background thread: `SyncEngine` is driven by
            // WorkManager and by controllers that wrap their calls in Dispatchers.IO (§12.1).
            defaultPriceProvider = { settingsStore.defaultPrice() },
        )
    }

    /**
     * Tells the user about a refuel as soon as any synchronization found one
     * (docs/SPEC.md §13.1). The notified ids live in `settings` — an id of a local
     * row is not a secret — so a repeated synchronization stays quiet.
     */
    val refuelNotifier: RefuelNotifier by lazy {
        RefuelNotifier(
            context = context,
            pending = { syncEngine.pendingRefuels() },
            notifiedIds = { settingsStore.notifiedRefuelIds() },
            markNotified = { id -> settingsStore.markRefuelNotified(id) },
            forgetNotified = { id -> settingsStore.forgetNotifiedRefuel(id) },
            io = Dispatchers.IO,
        )
    }

    /** "Consumption keeps rising" after a synchronization (PRD F30, docs/SPEC.md §31). */
    val consumptionAlertNotifier: pl.lk.toyotacompanion.notify.ConsumptionAlertNotifier by lazy {
        pl.lk.toyotacompanion.notify.ConsumptionAlertNotifier(
            context = context,
            readTrips = { syncEngine.trips() },
            lastAlertKey = { settingsStore.lastConsumptionAlert() },
            markAlerted = { settingsStore.saveLastConsumptionAlert(it) },
        )
    }

    /**
     * Saves the numbers of the home-screen widget and repaints it (PRD F31, docs/SPEC.md §32): from the archive and the
     * settings only, after every successful synchronization. Never breaks the synchronization.
     * "Synchronizuj teraz" calls it from the UI scope, so the preferences write and the repaint go to IO.
     */
    suspend fun refreshWidget() = withContext(Dispatchers.IO) {
        try {
            val trips = syncEngine.trips()
            val view = pl.lk.toyotacompanion.ui.consumptionView(trips, ZoneId.systemDefault())
            val forecast = syncEngine.refuelForecast()?.let {
                pl.lk.toyotacompanion.ui.forecastLine(pl.lk.toyotacompanion.ui.TimedForecast(it, java.time.Instant.now()), ZoneId.systemDefault())
            }
            val goals = pl.lk.toyotacompanion.ui.EcoGoals(settingsStore.ecoGoalConsumption(), settingsStore.ecoGoalEvPct())
            val goalLine = pl.lk.toyotacompanion.ui.ecoWidgetLine(
                pl.lk.toyotacompanion.ui.ecoProgress(goals, trips, ZoneId.systemDefault(), java.time.LocalDate.now(ZoneId.systemDefault())),
            )
            pl.lk.toyotacompanion.widget.WidgetSnapshotStore(context).save(
                pl.lk.toyotacompanion.widget.widgetSnapshot(view, forecast, settingsStore.defaultPrice(), goalLine),
            )
            pl.lk.toyotacompanion.widget.ConsumptionWidgetProvider.refresh(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The widget is a convenience; its failure is not the synchronization's.
        }
    }

    /**
     * One hook for both ways of synchronizing: `SyncWorker` and "Synchronizuj teraz"
     * call this runner, so the notification and the in-app prompt cannot miss a run
     * (docs/SPEC.md §13.1, §13.3). Neither may break the synchronization, hence the
     * guards inside [RefuelNotifier] and the ignored failures here.
     */
    val syncRunner: SyncRunner by lazy {
        SyncRunner {
            val outcome = syncEngine.fullSync()
            refuelNotifier.onSyncOutcome(outcome)
            consumptionAlertNotifier.onSyncOutcome(outcome)
            if (outcome is SyncOutcome.Ok) refreshWidget()
            if (outcome is SyncOutcome.Ok && outcome.refuelsDetected > 0) refuelPromptController.refresh()
            // New trips or a new reading change the forecast (docs/SPEC.md §20.2).
            if (outcome is SyncOutcome.Ok) {
                forecastController.refresh()
                consumptionController.refresh()
                ecoGoalsController.refresh()
                // New trips change the month's budget figures too (PRD F48, docs/SPEC.md §49).
                budgetController.refresh()
                // The month's budget may have crossed a threshold with the new trips (PRD F49, docs/SPEC.md §49).
                budgetAlertNotifier.onSyncOutcome(outcome)
                // The last completed week or month, once (PRD F54, docs/SPEC.md §54).
                periodSummaryNotifier.onSyncOutcome(outcome)
                // The tyre reminder, once per set and kind (PRD F56, docs/SPEC.md §56).
                tyreReminderNotifier.onSyncOutcome(outcome)
                // New trips get their weather without a tap (docs/SPEC.md §23).
                weatherController.autoFetch()
            }
            // A run of SyncWorker must reach an open status screen too (docs/SPEC.md §9.3);
            // during "Synchronizuj teraz" reload() is ignored and syncNow() refreshes itself.
            session.reload()
            outcome
        }
    }

    /**
     * Reads the trip list for the trips screen (docs/SPEC.md §10.1). A stable
     * reference, so the Compose read is not restarted on every recomposition, and
     * a plain read of the local archive: no Toyota call, no `refresh_status()`.
     */
    val tripsLoader: suspend () -> List<StoredTrip> = { syncEngine.trips() }

    /**
     * UI scope for the login and status screens; state changes happen on the main thread.
     * The controllers catch their own failures; the handler is only the last net, so a
     * missed exception costs a log line with its class name, never the app (no message,
     * no stack: it could carry personal data, docs/SECURITY.md).
     */
    private val uiScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.w(LOG_TAG, "Unhandled failure in the UI scope: ${e.javaClass.simpleName}")
        },
    )

    /**
     * Login + status screen state (docs/SPEC.md §9). Not a ViewModel: the Compose
     * ViewModel artifact is not a dependency of this module, so the controller
     * lives here. It stores the login and schedules the work on success.
     */
    val session: SessionController by lazy {
        SessionController(
            credentials = credentialStore,
            tokens = tokenStore,
            verifier = { username, password -> ToyotaGatewayFactory.verifyLogin(username, password) },
            runner = syncRunner,
            status = { syncEngine.status() },
            workManager = WorkManager.getInstance(context),
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /**
     * Refuel list and price form state (docs/SPEC.md §11.3). It shares the UI scope
     * with [session] on purpose: a price typed while a synchronization runs is
     * queued and stored, and leaving the form does not cancel the write.
     */
    val refuelController: RefuelController by lazy {
        RefuelController(
            pending = { syncEngine.pendingRefuels() },
            confirmRefuel = { id, price, liters, total, filledToFull, litersSource, station ->
                syncEngine.confirmRefuel(id, price, liters, total, filledToFull, litersSource, station)
            },
            dismissRefuel = { id -> syncEngine.dismissRefuel(id) },
            onResolved = { id ->
                uiScope.launch { refuelNotifier.cancel(id) }
                refuelPromptController.resolvedElsewhere(id)
            },
            scope = uiScope,
            io = Dispatchers.IO,
            stations = { syncEngine.stationSuggestions() },
        )
    }

    /**
     * Default fuel price screen state (docs/SPEC.md §12.3). It shares the UI scope
     * with [session]: storing the price and recomputing the trip costs survive
     * leaving the screen, and logging out only forgets the state, never the setting.
     */
    val defaultPriceController: DefaultPriceController by lazy {
        DefaultPriceController(
            read = { settingsStore.defaultPrice() },
            persist = { price ->
                if (price == null) settingsStore.clearDefaultPrice() else settingsStore.saveDefaultPrice(price)
            },
            // The widget shows the default price, so it is repainted once the costs are recounted.
            recompute = { syncEngine.recomputeCostsNow().also { refreshWidget() } },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /**
     * The "price right after a refuel" question (docs/SPEC.md §13.3). It shares the
     * UI scope with [session]: the write it starts is the ordinary
     * `SyncEngine.confirmRefuel`, and cancelling the notification belongs to the
     * same scope as the write that ended the wait.
     */
    val refuelPromptController: RefuelPromptController by lazy {
        RefuelPromptController(
            pending = { syncEngine.pendingRefuels() },
            confirmRefuel = { id, price, station -> syncEngine.confirmRefuel(id, price, null, null, null, station = station) },
            onResolved = { id -> uiScope.launch { refuelNotifier.cancel(id) } },
            onSaved = { session.reload() },
            scope = uiScope,
            io = Dispatchers.IO,
            stations = { syncEngine.stationSuggestions() },
        )
    }

    /**
     * Backup of the whole archive (PRD F7, F8, docs/SPEC.md §14). The file is the
     * `Uri` of the system picker, opened through the `ContentResolver`; reading stops
     * at [ARCHIVE_IMPORT_LIMIT_BYTES]. The date of the last export lives in
     * `SettingsStore`, next to the default price.
     */
    val backupController: BackupController by lazy {
        BackupController(
            // The expenses live in the app's own table, next to the archive; they travel in the same file.
            export = {
                syncEngine.exportArchive().copy(
                    expenses = database.expenseDao().all().map {
                        pl.lk.toyotacompanion.core.data.ArchiveExpense(it.id, it.dateEpochDay, it.category, it.amountPln, it.note)
                    },
                )
            },
            import = { snapshot ->
                val done = syncEngine.importArchive(snapshot)
                // Added only when the id is new, like the other rows: an import never overwrites what the phone has.
                database.expenseDao().insertAllIfAbsent(
                    snapshot.expenses.map {
                        pl.lk.toyotacompanion.data.db.ExpenseEntity(it.id, it.dateEpochDay, it.category, it.amountPln, it.note)
                    },
                )
                done
            },
            writeFile = { target, bytes ->
                // "wt": an existing file chosen again is replaced, not appended to.
                val out = context.contentResolver.openOutputStream(target.toUri(), "wt")
                    ?: throw IOException("no output stream")
                out.use { it.write(bytes) }
            },
            readFile = { source ->
                val input = context.contentResolver.openInputStream(source.toUri())
                    ?: throw IOException("no input stream")
                input.use { readArchiveBytes(it) }
            },
            readLastExport = { settingsStore.lastArchiveExport() },
            saveLastExport = { at -> settingsStore.saveLastArchiveExport(at) },
            loadTrips = tripsLoader,
            loadRefuels = { syncEngine.confirmedRefuels() },
            loadExpenses = { database.expenseDao().all().map { it.toExpense() } },
            // Auto Backup to the owner's Google Drive (ADR-015): dataChanged only schedules a pass.
            cloudBackupNow = { BackupManager(context).dataChanged() },
            zone = { ZoneId.systemDefault() },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** Electric share, years and seasons of the selected vehicle (PRD F10, F11, docs/SPEC.md §16-§17). */
    val statsController: StatsController by lazy {
        StatsController(
            read = tripsLoader,
            zone = { ZoneId.systemDefault() },
            scope = uiScope,
            io = Dispatchers.IO,
            defaultPrice = { settingsStore.defaultPrice() },
        )
    }

    /** The refuel forecast of the status screen (PRD F14, docs/SPEC.md §20). */
    val forecastController: ForecastController by lazy {
        ForecastController(read = { syncEngine.refuelForecast() }, scope = uiScope, io = Dispatchers.IO)
    }

    /** The same shared controller as [gateway]; one trip's route per request (docs/SPEC.md §27). */
    private val routeGateway = pl.lk.toyotacompanion.core.routes.TripRouteGateway { vin, tripId ->
        ToyotaGatewayFactory.createRoutes(sharedController()).route(vin, tripId)
    }

    /** The GPS route of the trip whose map is open, from the phone or one read-only Toyota GET (docs/SPEC.md §27). */
    val tripRouteController: TripRouteController by lazy {
        TripRouteController(
            readTrips = tripsLoader,
            repository = RoomTripRouteRepository(database.tripRouteDao()),
            gateway = routeGateway,
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** The consumption the maker declares, typed by the owner (docs/SPEC.md §30). */
    val declaredConsumptionController: DeclaredConsumptionController by lazy {
        DeclaredConsumptionController(read = { settingsStore.declaredConsumption() }, save = { settingsStore.saveDeclaredConsumption(it) })
    }

    /** The monthly eco goals of the owner and the month's figures against them (PRD F39, docs/SPEC.md §40). */
    val ecoGoalsController: EcoGoalsController by lazy {
        EcoGoalsController(
            readGoals = {
                EcoGoals(
                    settingsStore.ecoGoalConsumption(),
                    settingsStore.ecoGoalEvPct(),
                    settingsStore.ecoGoalConsumptionSince(),
                    settingsStore.ecoGoalEvSince(),
                )
            },
            saveGoals = {
                settingsStore.saveEcoGoals(it.maxConsumption, it.minEvSharePct, it.consumptionSince, it.evSince)
            },
            readTrips = tripsLoader,
            zone = { ZoneId.systemDefault() },
            scope = uiScope,
            io = Dispatchers.IO,
            // The widget has a line for the goals, so it is repainted when they change.
            onSaved = { uiScope.launch { refreshWidget() } },
        )
    }

    /**
     * The monthly budget for fuel and expenses (PRD F48, docs/SPEC.md §49): the limit from the phone settings and the
     * month computed from the archive and the expenses table. No network, so the car is never woken.
     */
    val budgetController: pl.lk.toyotacompanion.ui.BudgetController by lazy {
        pl.lk.toyotacompanion.ui.BudgetController(
            readLimit = { settingsStore.monthlyBudget() },
            saveLimit = { settingsStore.saveMonthlyBudget(it) },
            readAlert = { settingsStore.budgetAlert() },
            saveAlert = { settingsStore.saveBudgetAlert(it) },
            readTrips = tripsLoader,
            readExpenses = { database.expenseDao().all().map { it.toExpense() } },
            zone = { ZoneId.systemDefault() },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /**
     * The monthly budget notification (PRD F49, docs/SPEC.md §49): one 80% warning and one 100% alert per month.
     * Triggered by a successful sync and by an expense change; reuses the existing notification permission and
     * never touches the network or the car.
     */
    /** The period summary notification (PRD F54, docs/SPEC.md §54): the archive and the expenses, read on IO; the setting is in `settings`. */
    private val periodSummaryNotifier: pl.lk.toyotacompanion.notify.PeriodSummaryNotifier by lazy {
        pl.lk.toyotacompanion.notify.PeriodSummaryNotifier(
            context = context,
            readTrips = tripsLoader,
            readExpenses = { database.expenseDao().all().map { it.toExpense() }.map { it.date to it.amount } },
            period = { settingsStore.periodSummaryKind() },
            lastKey = { settingsStore.lastPeriodSummary() },
            markSent = { settingsStore.saveLastPeriodSummary(it) },
        )
    }

    /** The tyre reminder (PRD F56, docs/SPEC.md §56): the thresholds and the keys are settings; the archive is read on IO. */
    private val tyreReminderNotifier: pl.lk.toyotacompanion.notify.TyreReminderNotifier by lazy {
        pl.lk.toyotacompanion.notify.TyreReminderNotifier(
            context = context,
            readTrips = tripsLoader,
            readChangesText = { settingsStore.tyreChangesText() },
            readThresholdsText = { settingsStore.tyreThresholdsText() },
            readKeysText = { settingsStore.tyreReminderKeysText() },
            saveKeysText = { settingsStore.saveTyreReminderKeysText(it) },
        )
    }

    /** The tyre card of "Wydatki" (PRD F56): fittings, mileage and thresholds, all on the phone. */
    val tyreController: pl.lk.toyotacompanion.ui.TyreController by lazy {
        pl.lk.toyotacompanion.ui.TyreController(
            readTrips = tripsLoader,
            readChangesText = { settingsStore.tyreChangesText() },
            saveChangesText = { settingsStore.saveTyreChangesText(it) },
            readThresholdsText = { settingsStore.tyreThresholdsText() },
            saveThresholdsText = { settingsStore.saveTyreThresholdsText(it) },
            readKeysText = { settingsStore.tyreReminderKeysText() },
            saveKeysText = { settingsStore.saveTyreReminderKeysText(it) },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** The owner's choice in "Opcje" for the period summary (PRD F54). */
    val periodSummarySettings: pl.lk.toyotacompanion.ui.PeriodSummarySettings by lazy {
        pl.lk.toyotacompanion.ui.PeriodSummarySettings(
            read = { settingsStore.periodSummaryKind() },
            save = { settingsStore.savePeriodSummaryKind(it) },
        )
    }

    private val budgetAlertNotifier: pl.lk.toyotacompanion.notify.BudgetAlertNotifier by lazy {
        pl.lk.toyotacompanion.notify.BudgetAlertNotifier(
            context = context,
            readLimit = { settingsStore.monthlyBudget() },
            readTrips = tripsLoader,
            readExpenses = { database.expenseDao().all().map { it.toExpense() } },
            lastAlertKey = { settingsStore.lastBudgetAlert() },
            markAlerted = { settingsStore.saveLastBudgetAlert(it) },
            alertEnabled = { settingsStore.budgetAlert() },
        )
    }

    /** One year of driving on one page (PRD F28, docs/SPEC.md §29). */
    val yearSummaryController: YearSummaryController by lazy {
        YearSummaryController(
            read = tripsLoader,
            zone = { ZoneId.systemDefault() },
            // Expenses of the year join the fuel in the total cost (PRD F38, docs/SPEC.md §39).
            readExpenses = { database.expenseDao().all().map { it.toExpense() } },
            // The owner's estimate sits apart from the cash (PRD F55, docs/SPEC.md §55).
            readValuation = { settingsStore.carValuation() },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** The owner's car valuation (PRD F55, docs/SPEC.md §55): a plain phone setting, no network and no car. */
    val carValuationController: CarValuationController by lazy {
        CarValuationController(
            read = { settingsStore.carValuation() },
            persist = { settingsStore.saveCarValuation(it) },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** Saves the routes of older trips before Toyota removes them (PRD F32, docs/SPEC.md §33). */
    val routeBackfillController: RouteBackfillController by lazy {
        RouteBackfillController(
            readTrips = tripsLoader,
            repository = RoomTripRouteRepository(database.tripRouteDao()),
            gateway = routeGateway,
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** The consumption heat map of one repeated route, from the routes stored on the phone (PRD F33, docs/SPEC.md §34). */
    val routeHeatController: RouteHeatController by lazy {
        RouteHeatController(
            readTrips = tripsLoader,
            repository = RoomTripRouteRepository(database.tripRouteDao()),
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** Station prices for the price fields, fetched only on a tap (docs/SPEC.md §25). */
    val stationPriceController: StationPriceController by lazy {
        StationPriceController(gateway = FuelPriceGatewayFactory.create(), scope = uiScope, io = Dispatchers.IO)
    }

    /** Average consumption and monthly bars of the status screen (docs/SPEC.md §24). */
    val consumptionController: ConsumptionController by lazy {
        ConsumptionController(read = tripsLoader, zone = { ZoneId.systemDefault() }, scope = uiScope, io = Dispatchers.IO)
    }

    /** Eco score, repeated routes and fuel anomalies of the selected vehicle (PRD F12, F13, docs/SPEC.md §19). */
    val drivingStyleController: DrivingStyleController by lazy {
        DrivingStyleController(
            read = tripsLoader,
            zone = { ZoneId.systemDefault() },
            scope = uiScope,
            io = Dispatchers.IO,
        )
    }

    /** Weather cache and the one explicit fetch of missing days (PRD F15, docs/SPEC.md §23). */
    val weatherController: WeatherController by lazy {
        WeatherController(
            readTrips = tripsLoader,
            cache = RoomWeatherRepository(database.weatherDao()),
            gateway = WeatherGatewayFactory.create(),
            zone = { ZoneId.systemDefault() },
            scope = uiScope,
            io = Dispatchers.IO,
            autoEnabled = { settingsStore.weatherAuto() },
            saveAuto = { settingsStore.saveWeatherAuto(it) },
        )
    }

    /** Places and stops from the archived trips, never from a Toyota position request (PRD F17, §18). */
    val parkingController: ParkingController by lazy {
        ParkingController(read = tripsLoader, scope = uiScope, io = Dispatchers.IO)
    }

    /** The refuel log and its fuel economy check (PRD F9, docs/SPEC.md §15). */
    val refuelLogController: RefuelLogController by lazy {
        RefuelLogController(
            read = { syncEngine.refuelLog() },
            amend = { id, liters, total, full, station ->
                syncEngine.amendRefuel(id, liters, total, full, station)
            },
            scope = uiScope,
            io = Dispatchers.IO,
            readLevels = { syncEngine.fuelLevels() },
        )
    }

    /**
     * The one-shot decision of the status screen (docs/SPEC.md §13.2): the rule is
     * pure, the "already asked" flag comes from the settings file on the IO
     * dispatcher, and a refusal is never asked about again.
     */
    val askNotificationPermission: suspend () -> Boolean = {
        val asked = withContext(Dispatchers.IO) { settingsStore.notificationsAsked() }
        val decision = shouldAskNotificationPermission(
            sdkInt = Build.VERSION.SDK_INT,
            granted = notificationPermissionGranted(context),
            askedBefore = asked,
        )
        if (decision) withContext(Dispatchers.IO) { settingsStore.markNotificationsAsked() }
        decision
    }

    private companion object {
        const val SECRETS_PREFS = "secrets"
        const val SETTINGS_PREFS = "settings"
        const val DATABASE_NAME = "toyota-companion.db"
        const val LOG_TAG = "ToyotaCompanion"
    }
}
