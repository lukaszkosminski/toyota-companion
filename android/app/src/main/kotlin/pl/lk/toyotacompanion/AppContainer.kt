package pl.lk.toyotacompanion

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
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
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.sync.NoCredentialsException
import pl.lk.toyotacompanion.core.sync.SyncEngine
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.core.sync.ToyotaGateway
import pl.lk.toyotacompanion.core.sync.ToyotaGatewayFactory
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
import pl.lk.toyotacompanion.ui.DefaultPriceController
import pl.lk.toyotacompanion.ui.RefuelController
import pl.lk.toyotacompanion.ui.RefuelPromptController
import pl.lk.toyotacompanion.ui.SessionController
import pl.lk.toyotacompanion.ui.readArchiveBytes

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

    val credentialStore: CredentialStore by lazy { CredentialStore(secretStore) }
    val tokenStore: KeystoreTokenStore by lazy { KeystoreTokenStore(secretStore) }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .setDriver(AndroidSQLiteDriver())
            .build()
    }

    /** Reads the stored login at call time, so a password change needs no restart. */
    private val gateway = object : ToyotaGateway {
        private fun current(): ToyotaGateway {
            val credentials = credentialStore.load() ?: throw NoCredentialsException()
            return ToyotaGatewayFactory.create(credentials.username, credentials.password, tokenStore)
        }

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
            if (outcome is SyncOutcome.Ok && outcome.refuelsDetected > 0) refuelPromptController.refresh()
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
            confirmRefuel = { id, price, liters, total, filledToFull, litersSource ->
                syncEngine.confirmRefuel(id, price, liters, total, filledToFull, litersSource)
            },
            dismissRefuel = { id -> syncEngine.dismissRefuel(id) },
            onResolved = { id ->
                uiScope.launch { refuelNotifier.cancel(id) }
                refuelPromptController.resolvedElsewhere(id)
            },
            scope = uiScope,
            io = Dispatchers.IO,
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
            recompute = { syncEngine.recomputeCostsNow() },
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
            confirmRefuel = { id, price -> syncEngine.confirmRefuel(id, price, null, null, null) },
            onResolved = { id -> uiScope.launch { refuelNotifier.cancel(id) } },
            onSaved = { session.reload() },
            scope = uiScope,
            io = Dispatchers.IO,
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
            export = { syncEngine.exportArchive() },
            import = { snapshot -> syncEngine.importArchive(snapshot) },
            writeFile = { target, bytes ->
                // "wt": an existing file chosen again is replaced, not appended to.
                val out = context.contentResolver.openOutputStream(Uri.parse(target), "wt")
                    ?: throw IOException("no output stream")
                out.use { it.write(bytes) }
            },
            readFile = { source ->
                val input = context.contentResolver.openInputStream(Uri.parse(source))
                    ?: throw IOException("no input stream")
                input.use { readArchiveBytes(it) }
            },
            readLastExport = { settingsStore.lastArchiveExport() },
            saveLastExport = { at -> settingsStore.saveLastArchiveExport(at) },
            loadTrips = tripsLoader,
            zone = { ZoneId.systemDefault() },
            scope = uiScope,
            io = Dispatchers.IO,
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
