package pl.lk.toyotacompanion.data.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.core.data.CostRepository
import pl.lk.toyotacompanion.core.data.FuelReadingRepository
import pl.lk.toyotacompanion.core.data.RefuelRepository
import pl.lk.toyotacompanion.core.data.RepositoryContract
import pl.lk.toyotacompanion.core.data.RepositoryStores
import pl.lk.toyotacompanion.core.data.SyncStateRepository
import pl.lk.toyotacompanion.core.data.TripRepository
import pl.lk.toyotacompanion.core.data.VehicleRepository

private class RoomStores(db: AppDatabase) : RepositoryStores {
    override val trips: TripRepository = RoomTripRepository(db.tripDao())
    override val refuels: RefuelRepository = RoomRefuelRepository(db.refuelDao())
    override val readings: FuelReadingRepository = RoomFuelReadingRepository(db.fuelReadingDao())
    override val costs: CostRepository = RoomCostRepository(db.tripCostDao())
    override val vehicles: VehicleRepository = RoomVehicleRepository(db.vehicleDao())
    override val state: SyncStateRepository = RoomSyncStateRepository(db.syncStateDao())
}

/** Every `RepositoryContract` scenario on an in-memory Room database (fresh per test). */
@RunWith(RobolectricTestRunner::class)
class RoomRepositoryContractTest {
    private lateinit var db: AppDatabase
    private lateinit var contract: RepositoryContract

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
        contract = RepositoryContract { RoomStores(db) }
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun `upsert counts only new ids`() = runTest { contract.`upsert counts only new ids`() }

    @Test
    fun `upsert replaces the row of an existing id`() = runTest { contract.`upsert replaces the row of an existing id`() }

    @Test
    fun `trips are separated per vin`() = runTest { contract.`trips are separated per vin`() }

    @Test
    fun `all is oldest first and allSince is newest first`() = runTest { contract.`all is oldest first and allSince is newest first`() }

    @Test
    fun `idsBetween is inclusive at both ends`() = runTest { contract.`idsBetween is inclusive at both ends`() }

    @Test
    fun `addIfAbsent ignores a duplicate refuel id`() = runTest { contract.`addIfAbsent ignores a duplicate refuel id`() }

    @Test
    fun `update keeps the vehicle`() = runTest { contract.`update keeps the vehicle`() }

    @Test
    fun `confirmedUpTo is inclusive and ordered by ts then id`() = runTest { contract.`confirmedUpTo is inclusive and ordered by ts then id`() }

    @Test
    fun `pending is newest first and vin filtered`() = runTest { contract.`pending is newest first and vin filtered`() }

    @Test
    fun `reading of the same vin and ts is stored once`() = runTest { contract.`reading of the same vin and ts is stored once`() }

    @Test
    fun `latest is the newest reading of that vin`() = runTest { contract.`latest is the newest reading of that vin`() }

    @Test
    fun `since is ascending and vin filtered`() = runTest { contract.`since is ascending and vin filtered`() }

    @Test
    fun `costs are replaced per trip id`() = runTest { contract.`costs are replaced per trip id`() }

    @Test
    fun `vehicle upsert keeps firstSeen`() = runTest { contract.`vehicle upsert keeps firstSeen`() }

    @Test
    fun `sync state round trips`() = runTest { contract.`sync state round trips`() }
}
