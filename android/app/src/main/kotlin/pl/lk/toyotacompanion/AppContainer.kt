package pl.lk.toyotacompanion

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.WorkManager
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.sync.NoCredentialsException
import pl.lk.toyotacompanion.core.sync.SyncEngine
import pl.lk.toyotacompanion.core.sync.ToyotaGateway
import pl.lk.toyotacompanion.core.sync.ToyotaGatewayFactory
import pl.lk.toyotacompanion.data.db.AppDatabase
import pl.lk.toyotacompanion.data.db.RoomCostRepository
import pl.lk.toyotacompanion.data.db.RoomFuelReadingRepository
import pl.lk.toyotacompanion.data.db.RoomRefuelRepository
import pl.lk.toyotacompanion.data.db.RoomSyncStateRepository
import pl.lk.toyotacompanion.data.db.RoomTripRepository
import pl.lk.toyotacompanion.data.db.RoomVehicleRepository
import pl.lk.toyotacompanion.security.AndroidKeystoreCipher
import pl.lk.toyotacompanion.security.CredentialStore
import pl.lk.toyotacompanion.security.EncryptedSecretStore
import pl.lk.toyotacompanion.security.KeystoreTokenStore
import pl.lk.toyotacompanion.security.SecretCipher
import pl.lk.toyotacompanion.settings.SettingsStore
import pl.lk.toyotacompanion.sync.SyncRunner
import pl.lk.toyotacompanion.ui.DefaultPriceController
import pl.lk.toyotacompanion.ui.RefuelController
import pl.lk.toyotacompanion.ui.SessionController

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

    val syncRunner: SyncRunner by lazy { SyncRunner { syncEngine.fullSync() } }

    /**
     * Reads the trip list for the trips screen (docs/SPEC.md §10.1). A stable
     * reference, so the Compose read is not restarted on every recomposition, and
     * a plain read of the local archive: no Toyota call, no `refresh_status()`.
     */
    val tripsLoader: suspend () -> List<StoredTrip> = { syncEngine.trips() }

    /** UI scope for the login and status screens; state changes happen on the main thread. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            confirmRefuel = { id, price, liters, total, filledToFull ->
                syncEngine.confirmRefuel(id, price, liters, total, filledToFull)
            },
            dismissRefuel = { id -> syncEngine.dismissRefuel(id) },
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

    private companion object {
        const val SECRETS_PREFS = "secrets"
        const val SETTINGS_PREFS = "settings"
        const val DATABASE_NAME = "toyota-companion.db"
    }
}
