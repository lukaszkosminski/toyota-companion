package pl.lk.toyotacompanion

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.WorkManager
import java.time.Instant
import java.time.LocalDate
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
import pl.lk.toyotacompanion.security.Credentials
import pl.lk.toyotacompanion.security.EncryptedSecretStore
import pl.lk.toyotacompanion.security.KeystoreTokenStore
import pl.lk.toyotacompanion.security.SecretCipher
import pl.lk.toyotacompanion.sync.SyncRunner
import pl.lk.toyotacompanion.sync.SyncScheduler

/** Hand-wired dependencies (no DI framework). Everything is lazy: nothing touches Keystore or disk at app start. */
class AppContainer(
    private val context: Context,
    cipherFactory: () -> SecretCipher = { AndroidKeystoreCipher() },
) {
    private val secretStore by lazy {
        EncryptedSecretStore(context.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE), cipherFactory())
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
        )
    }

    val syncRunner: SyncRunner by lazy { SyncRunner { syncEngine.fullSync() } }

    /** Called by the (future) login screen: store the login, then start background sync plus one run now. */
    fun onLoggedIn(credentials: Credentials) {
        credentialStore.save(credentials)
        SyncScheduler.onLogin(WorkManager.getInstance(context))
    }

    private companion object {
        const val SECRETS_PREFS = "secrets"
        const val DATABASE_NAME = "toyota-companion.db"
    }
}
