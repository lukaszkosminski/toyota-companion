package pl.lk.toyotacompanion.core.sync

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import pl.lk.toyotacompanion.core.data.InMemoryStores
import pl.lk.toyotacompanion.core.data.SyncConfig
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.TripRepository
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip

private const val VIN = "VINAAA111"
private const val OTHER_VIN = "VINBBB222"
private val NOW: Instant = Instant.parse("2026-10-20T12:00:00Z")

private fun record(id: String, hoursAgo: Long, vin: String = VIN) = TripRecord(
    Trip(id, NOW.minusSeconds(hoursAgo * 3_600), NOW.minusSeconds(hoursAgo * 3_600 - 1_800), 14.6, BigDecimal("0.60"), null),
    vin,
)

private fun vehicle(vin: String = VIN) = VehicleRecord(vin, "TOYOTA", "Corolla Hybrid", "HEV", false, NOW, NOW)

/** Counts full reads of the archive; [gate] holds the next read until it completes. */
private class CountingTrips(
    private val delegate: TripRepository,
    var gate: CompletableDeferred<Unit>? = null,
    var failAfterWrite: Boolean = false,
) : TripRepository by delegate {
    var fullReads = 0

    override suspend fun all(vin: String): List<TripRecord> {
        fullReads++
        gate?.await()
        return delegate.all(vin)
    }

    override suspend fun upsertAll(records: List<TripRecord>): Int {
        val added = delegate.upsertAll(records)
        if (failAfterWrite) throw IllegalStateException("disk full after the write")
        return added
    }
}

/** C22 Q6: one read of the archive for every reader after a synchronization, never a stale list. */
class SyncEngineTripsCacheTest {

    private val stores = InMemoryStores()
    private val counting = CountingTrips(stores.trips)
    private val gateway = FakeToyotaGateway(fleet = listOf(vehicle()))

    private val engine = SyncEngine(
        gateway = gateway,
        vehicles = stores.vehicles,
        trips = counting,
        refuels = stores.refuels,
        readings = stores.readings,
        costs = stores.costs,
        state = stores.state,
        config = SyncConfig(defaultPrice = BigDecimal("6.19")),
        clock = { NOW },
    )

    private suspend fun seed(vararg trips: TripRecord) {
        stores.trips.upsertAll(trips.toList())
        stores.state.save(SyncState(vin = VIN))
    }

    @Test
    fun `readers share one read of the archive`() = runTest {
        seed(record("t1", 5), record("t2", 3))

        val first = engine.trips()
        val second = engine.trips()
        engine.trips()

        assertEquals(1, counting.fullReads)
        assertEquals(listOf("t2", "t1"), second.map { it.record.id })
        assertEquals(first, second)
    }

    @Test
    fun `readers arriving together wait for one read`() = runTest {
        seed(record("t1", 5))
        val gate = CompletableDeferred<Unit>()
        counting.gate = gate

        val readers = List(4) { async { engine.trips() } }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        val lists = readers.awaitAll()

        assertEquals(1, counting.fullReads)
        assertEquals(1, lists.map { it.map { t -> t.record.id } }.distinct().size)
    }

    @Test
    fun `a synchronization with new trips is seen by the next read`() = runTest {
        seed(record("t1", 50))
        engine.trips()
        gateway.tripsProvider = { _, _, _ -> listOf(record("t-new", 1)) }

        assertIs<SyncOutcome.Ok>(engine.fullSync())

        assertEquals(listOf("t-new", "t1"), engine.trips().map { it.record.id })
    }

    @Test
    fun `a write that fails half way still drops the snapshot`() = runTest {
        seed(record("t1", 50))
        engine.trips()
        gateway.tripsProvider = { _, _, _ -> listOf(record("t-new", 1)) }
        counting.failAfterWrite = true

        val failed = assertIs<SyncOutcome.Failed>(engine.fullSync())

        assertEquals(SyncErrorKind.STORAGE, failed.error.kind)
        assertEquals(listOf("t-new", "t1"), engine.trips().map { it.record.id })
    }

    @Test
    fun `a confirmed price recounts the costs and the next read carries them`() = runTest {
        seed(record("t1", 2))
        stores.refuels.addIfAbsent(
            Refuel("r1", NOW.minusSeconds(86_400), 9_900.0, 20, 80, null, null, null, RefuelStatus.PENDING),
            VIN,
        )
        engine.recomputeCostsNow()
        val before = engine.trips().single().cost
        val readsBefore = counting.fullReads

        assertIs<pl.lk.toyotacompanion.core.sync.RefuelChange.Done>(
            engine.confirmRefuel("r1", pricePerLiter = BigDecimal("7.00"), liters = BigDecimal("30")),
        )
        val after = assertNotNull(engine.trips().single().cost)

        assertEquals(0, BigDecimal("6.190").compareTo(assertNotNull(before).pricePerLiter))
        // The default 6,19 is the price of the 8,6 l (20 % of 43 l) already in the tank (ADR-005 amendment 2026-11-08):
        // (8,6 x 6,19 + 30 x 7,00) / 38,6 = 263,234 / 38,6 = 6,8195 -> 6,820.
        assertEquals(0, BigDecimal("6.820").compareTo(after.pricePerLiter))
        assertEquals(readsBefore + 2, counting.fullReads) // the recount itself and the next list
    }

    @Test
    fun `an imported archive is seen by the next read`() = runTest {
        seed(record("t1", 50))
        val source = InMemoryStores()
        source.trips.upsertAll(listOf(record("t-imported", 100)))
        source.vehicles.upsert(vehicle())
        source.state.save(SyncState(vin = VIN))
        val snapshot = SyncEngine(
            gateway = FakeToyotaGateway(),
            vehicles = source.vehicles,
            trips = source.trips,
            refuels = source.refuels,
            readings = source.readings,
            costs = source.costs,
            state = source.state,
            clock = { NOW },
        ).exportArchive()
        engine.trips()

        engine.importArchive(snapshot)

        assertEquals(listOf("t1", "t-imported"), engine.trips().map { it.record.id })
    }

    @Test
    fun `another vehicle gets its own list`() = runTest {
        seed(record("t1", 5), record("t-other", 3, vin = OTHER_VIN))
        assertEquals(listOf("t1"), engine.trips().map { it.record.id })

        engine.selectVehicle(OTHER_VIN)

        assertEquals(listOf("t-other"), engine.trips().map { it.record.id })
    }
}
