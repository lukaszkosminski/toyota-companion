@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.sync

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import pl.lk.toyotacompanion.core.data.ArchiveDecode
import pl.lk.toyotacompanion.core.data.ArchiveReading
import pl.lk.toyotacompanion.core.data.ArchiveRefuel
import pl.lk.toyotacompanion.core.data.ArchiveSnapshot
import pl.lk.toyotacompanion.core.data.InMemoryStores
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.data.decodeArchive
import pl.lk.toyotacompanion.core.data.encodeArchive

/**
 * The backup of the whole archive (PRD F7, docs/SPEC.md §14): what the phone holds is
 * written out, and a file is added without destroying what the phone already decided.
 * The collision rule of docs/SPEC.md §14.1 lives in this layer, so it has no Python mirror.
 */
private const val VIN = "VINAAA111"

private const val OTHER_VIN = "VINBBB222"

private val DAY: Instant = Instant.parse("2026-10-20T12:00:00Z")

private fun vehicle(vin: String): VehicleRecord = VehicleRecord(vin, "TOYOTA", "Corolla Hybrid", "HEV", false, DAY, DAY)

private fun trip(id: String, vin: String, start: Instant = DAY.minusSeconds(3_600)): TripRecord = TripRecord(
    trip = Trip(id = id, start = start, end = start.plusSeconds(1_800), distanceKm = 14.6, fuelL = BigDecimal("0.600"), evDistanceKm = null),
    vin = vin,
)

private fun refuel(
    id: String,
    status: RefuelStatus,
    price: String? = "6.190",
    liters: String? = "41.230",
): Refuel = Refuel(
    id = id,
    ts = DAY.minusSeconds(86_400),
    odometerKm = 9_900.0,
    levelBefore = 20,
    levelAfter = 80,
    liters = liters?.let(::BigDecimal),
    pricePerLiter = price?.let(::BigDecimal),
    totalCost = null,
    status = status,
    litersSource = if (liters == null) null else LitersSource.RECEIPT,
)

private fun reading(ts: Instant): FuelReading = FuelReading(ts = ts, levelPct = 62, odometerKm = 9_900.0)

private fun snapshotOf(
    refuels: List<ArchiveRefuel> = emptyList(),
    trips: List<TripRecord> = emptyList(),
    vehicles: List<VehicleRecord> = listOf(vehicle(VIN)),
): ArchiveSnapshot = ArchiveSnapshot(DAY, vehicles, trips, refuels, emptyList())

private suspend fun seedFullArchive(stores: InMemoryStores) {
    stores.vehicles.upsert(vehicle(VIN))
    stores.vehicles.upsert(vehicle(OTHER_VIN))
    stores.trips.upsertAll(listOf(trip("t1", VIN), trip("t2", VIN, DAY.minusSeconds(86_400)), trip("t3", OTHER_VIN)))
    stores.refuels.addIfAbsent(refuel("r1", RefuelStatus.CONFIRMED), VIN)
    stores.refuels.addIfAbsent(refuel("r2", RefuelStatus.PENDING, price = null, liters = null), VIN)
    stores.refuels.addIfAbsent(refuel("r3", RefuelStatus.CONFIRMED), OTHER_VIN)
    stores.readings.addIfAbsent(reading(DAY.minusSeconds(600)), VIN)
    stores.readings.addIfAbsent(reading(DAY), OTHER_VIN)
    stores.state.save(SyncState(vin = VIN))
}

/** The rows of every vehicle as a set, so an import is compared by identity, not by order. */
private suspend fun tripsOf(stores: InMemoryStores): Set<Pair<String, String>> =
    stores.vehicles.all().flatMap { vehicle -> stores.trips.all(vehicle.vin).map { vehicle.vin to it.id } }.toSet()

private suspend fun refuelsOf(stores: InMemoryStores): Set<Triple<String, String, RefuelStatus>> =
    stores.vehicles.all()
        .flatMap { vehicle -> stores.refuels.all(vehicle.vin).map { Triple(vehicle.vin, it.id, it.status) } }
        .toSet()

private suspend fun readingsOf(stores: InMemoryStores): Set<Pair<String, Instant>> =
    stores.vehicles.all()
        .flatMap { vehicle -> stores.readings.since(Instant.EPOCH, vehicle.vin).map { vehicle.vin to it.ts } }
        .toSet()

private class Ctx(
    val gateway: FakeToyotaGateway,
    val stores: InMemoryStores,
    val clock: FakeClock,
    val engine: SyncEngine,
)

private fun ctx(
    gateway: FakeToyotaGateway = FakeToyotaGateway(),
    stores: InMemoryStores = InMemoryStores(),
    start: Instant = DAY,
    defaultPrice: BigDecimal? = BigDecimal("6.19"),
): Ctx {
    val clock = FakeClock(start)
    val engine = SyncEngine(
        gateway = gateway,
        vehicles = stores.vehicles,
        trips = stores.trips,
        refuels = stores.refuels,
        readings = stores.readings,
        costs = stores.costs,
        state = stores.state,
        defaultPriceProvider = { defaultPrice },
        clock = { clock() },
    )
    return Ctx(gateway, stores, clock, engine)
}

private val NO_COUNTS = ArchiveImportCounts(0, 0, 0, 0, 0, 0, 0, 0)

class SyncEngineArchiveTest {
    @Test
    fun `exporting an empty archive writes nothing`() = runTest {
        val c = ctx()

        val snapshot = c.engine.exportArchive()

        assertEquals(DAY, snapshot.exportedAt)
        assertTrue(snapshot.vehicles.isEmpty())
        assertTrue(snapshot.trips.isEmpty())
        assertTrue(snapshot.refuels.isEmpty())
        assertTrue(snapshot.readings.isEmpty())
    }

    @Test
    fun `export collects the rows of every vehicle`() = runTest {
        val stores = InMemoryStores()
        seedFullArchive(stores)
        val c = ctx(stores = stores)

        val snapshot = c.engine.exportArchive()

        assertEquals(listOf(VIN, OTHER_VIN).sorted(), snapshot.vehicles.map { it.vin }.sorted())
        assertEquals(
            setOf(VIN to "t1", VIN to "t2", OTHER_VIN to "t3"),
            snapshot.trips.map { it.vin to it.id }.toSet(),
        )
        assertEquals(
            setOf(VIN to "r1", VIN to "r2", OTHER_VIN to "r3"),
            snapshot.refuels.map { it.vin to it.refuel.id }.toSet(),
        )
        assertEquals(
            setOf(VIN to DAY.minusSeconds(600), OTHER_VIN to DAY),
            snapshot.readings.map { it.vin to it.reading.ts }.toSet(),
        )
    }

    @Test
    fun `a file written here restores the same rows on an empty phone`() = runTest {
        val source = InMemoryStores()
        seedFullArchive(source)
        val snapshot = ctx(stores = source).engine.exportArchive()

        val bytes = encodeArchive(snapshot)
        val decoded = assertIs<ArchiveDecode.Ok>(decodeArchive(bytes))

        val target = InMemoryStores()
        target.state.save(SyncState(vin = VIN))
        val counts = assertIs<ArchiveImport.Done>(ctx(stores = target).engine.importArchive(decoded.snapshot)).counts

        assertEquals(2, counts.vehiclesAdded)
        assertEquals(3, counts.tripsAdded)
        assertEquals(3, counts.refuelsAdded)
        assertEquals(2, counts.readingsAdded)
        assertEquals(1, counts.otherVehicleTrips, "t3 belongs to the other vehicle")
        assertEquals(2, counts.tripsCosted, "the two trips of the selected vehicle are priced")
        assertEquals(snapshot.trips.map { it.vin to it.id }.toSet(), tripsOf(target))
        assertEquals(snapshot.refuels.map { Triple(it.vin, it.refuel.id, it.refuel.status) }.toSet(), refuelsOf(target))
        assertEquals(snapshot.readings.map { it.vin to it.reading.ts }.toSet(), readingsOf(target))
        assertEquals(2, target.costs.count(), "the costs of the selected vehicle were recomputed")
    }

    @Test
    fun `importing the same file again adds nothing`() = runTest {
        val source = InMemoryStores()
        seedFullArchive(source)
        val snapshot = ctx(stores = source).engine.exportArchive()

        val target = InMemoryStores()
        target.state.save(SyncState(vin = VIN))
        val engine = ctx(stores = target).engine
        engine.importArchive(snapshot)

        val again = assertIs<ArchiveImport.Done>(engine.importArchive(snapshot)).counts

        assertEquals(0, again.vehiclesAdded)
        assertEquals(0, again.tripsAdded)
        assertEquals(0, again.refuelsAdded)
        assertEquals(0, again.refuelsUpdated)
        assertEquals(0, again.refuelsSkipped)
        assertEquals(0, again.readingsAdded)
        assertEquals(3, target.trips.all(VIN).size + target.trips.all(OTHER_VIN).size, "still the same three trips")
        assertEquals(3, target.refuels.count())
        assertEquals(2, target.readings.count())
    }

    @Test
    fun `a priced refuel in the file wins over a pending one on the phone`() = runTest {
        val stores = InMemoryStores()
        stores.vehicles.upsert(vehicle(VIN))
        stores.refuels.addIfAbsent(refuel("r1", RefuelStatus.PENDING, price = null, liters = null), VIN)
        val c = ctx(stores = stores)

        val counts = assertIs<ArchiveImport.Done>(
            c.engine.importArchive(snapshotOf(refuels = listOf(ArchiveRefuel(VIN, refuel("r1", RefuelStatus.CONFIRMED))))),
        ).counts

        assertEquals(1, counts.refuelsUpdated)
        assertEquals(0, counts.refuelsAdded)
        assertEquals(0, counts.refuelsSkipped)
        val stored = requireNotNull(stores.refuels.byId("r1"))
        assertEquals(RefuelStatus.CONFIRMED, stored.status)
        assertEquals(BigDecimal("6.190"), stored.pricePerLiter)
    }

    @Test
    fun `a refuel refused on the phone stays refused`() = runTest {
        val stores = InMemoryStores()
        stores.vehicles.upsert(vehicle(VIN))
        stores.refuels.addIfAbsent(refuel("r1", RefuelStatus.DISMISSED, price = null, liters = "41.230"), VIN)
        val c = ctx(stores = stores)

        val counts = assertIs<ArchiveImport.Done>(
            c.engine.importArchive(snapshotOf(refuels = listOf(ArchiveRefuel(VIN, refuel("r1", RefuelStatus.CONFIRMED))))),
        ).counts

        assertEquals(1, counts.refuelsSkipped, "the screen has to say how many were skipped")
        assertEquals(0, counts.refuelsUpdated)
        val stored = requireNotNull(stores.refuels.byId("r1"))
        assertEquals(RefuelStatus.DISMISSED, stored.status, "the later decision of the user wins")
        assertNull(stored.pricePerLiter)
    }

    @Test
    fun `a pending refuel in the file never overwrites a priced one`() = runTest {
        val stores = InMemoryStores()
        stores.vehicles.upsert(vehicle(VIN))
        stores.refuels.addIfAbsent(refuel("r1", RefuelStatus.CONFIRMED, price = "6.190"), VIN)
        val c = ctx(stores = stores)

        val counts = assertIs<ArchiveImport.Done>(
            c.engine.importArchive(
                snapshotOf(refuels = listOf(ArchiveRefuel(VIN, refuel("r1", RefuelStatus.PENDING, price = null, liters = null)))),
            ),
        ).counts

        assertEquals(NO_COUNTS, counts.copy(tripsCosted = 0))
        val stored = requireNotNull(stores.refuels.byId("r1"))
        assertEquals(RefuelStatus.CONFIRMED, stored.status)
        assertEquals(BigDecimal("6.190"), stored.pricePerLiter)
    }

    @Test
    fun `trips of another vehicle are counted and kept for that vehicle`() = runTest {
        val stores = InMemoryStores()
        stores.vehicles.upsert(vehicle(VIN))
        stores.state.save(SyncState(vin = VIN))
        val c = ctx(stores = stores)

        val counts = assertIs<ArchiveImport.Done>(
            c.engine.importArchive(
                snapshotOf(
                    trips = listOf(trip("a1", VIN), trip("a2", VIN), trip("b1", OTHER_VIN), trip("b2", OTHER_VIN), trip("b3", OTHER_VIN)),
                    vehicles = listOf(vehicle(VIN), vehicle(OTHER_VIN)),
                ),
            ),
        ).counts

        assertEquals(5, counts.tripsAdded)
        assertEquals(3, counts.otherVehicleTrips)
        assertEquals(2, counts.tripsCosted, "only the selected vehicle is priced")
        assertEquals(3, stores.trips.all(OTHER_VIN).size, "the trips of the other vehicle are stored too")
    }

    @Test
    fun `import without a selected vehicle stores the rows and prices nothing`() = runTest {
        val stores = InMemoryStores()
        val c = ctx(stores = stores)

        val counts = assertIs<ArchiveImport.Done>(
            c.engine.importArchive(snapshotOf(trips = listOf(trip("t1", VIN), trip("t2", OTHER_VIN)))),
        ).counts

        assertEquals(0, counts.tripsCosted, "nothing to price without a selected vehicle")
        assertEquals(0, stores.costs.count())
        assertEquals(2, counts.tripsAdded)
        assertEquals(0, counts.otherVehicleTrips, "without a selected vehicle there is no other one to point at")
    }

    @Test
    fun `export waits for a running synchronization`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicle(VIN)))
        val gate = CompletableDeferred<Unit>()
        gateway.beforeVehicles = { gate.await() }
        val c = ctx(gateway = gateway)

        backgroundScope.launch { c.engine.fullSync() }
        advanceUntilIdle()

        val export = async { c.engine.exportArchive() }
        advanceUntilIdle()
        assertFalse(export.isCompleted, "the copy must wait until the synchronization released the mutex")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(VIN), export.await().vehicles.map { it.vin })
        assertEquals(1, gateway.vehiclesCalls)
    }

    @Test
    fun `an import adds the vehicle a file knows and the phone does not`() = runTest {
        val stores = InMemoryStores()
        stores.state.save(SyncState(vin = VIN))
        val c = ctx(stores = stores)

        val counts = assertIs<ArchiveImport.Done>(
            c.engine.importArchive(
                snapshotOf(
                    vehicles = listOf(vehicle(VIN), vehicle(OTHER_VIN)),
                    refuels = listOf(ArchiveRefuel(VIN, refuel("r1", RefuelStatus.CONFIRMED))),
                ),
            ),
        ).counts

        assertEquals(2, counts.vehiclesAdded)
        assertEquals(1, counts.refuelsAdded)
        assertEquals(2, stores.vehicles.count())
        assertEquals(VIN, requireNotNull(stores.vehicles.byVin(VIN)).vin)
    }
}
