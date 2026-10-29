@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.data

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

private const val VIN_A = "VINAAAA0000000001"
private const val VIN_B = "VINBBBB0000000001"

private val T0: Instant = Instant.parse("2026-10-01T10:00:00Z")

private fun tripRecord(id: String, start: Instant, vin: String = VIN_A): TripRecord = TripRecord(
    trip = Trip(id, start, start.plusSeconds(600), 10.0, BigDecimal("0.500"), null),
    vin = vin,
)

private fun refuel(id: String, ts: Instant, status: RefuelStatus = RefuelStatus.CONFIRMED): Refuel =
    Refuel(id, ts, 100.0, 20, 80, BigDecimal("25.8"), BigDecimal("6.19"), BigDecimal("159.70"), status)

private fun reading(ts: Instant, levelPct: Int): FuelReading = FuelReading(ts, levelPct, 100.0)

private fun cost(tripId: String, source: CostSource = CostSource.REFUEL): TripCost =
    TripCost(tripId, BigDecimal("3.10"), BigDecimal("6.190"), "refuel-1", source)

/**
 * Behavioral contract every repository implementation must satisfy — the rules
 * the Room implementation (app module) has to reproduce exactly:
 * deduplication by id, per-vehicle separation, ordering, `firstSeen` retention.
 *
 * Subclasses: [InMemoryRepositoriesTest] now, `Room...Test` when the database lands.
 */
abstract class RepositoryContractTest {
    abstract fun trips(): TripRepository

    abstract fun refuels(): RefuelRepository

    abstract fun readings(): FuelReadingRepository

    abstract fun costs(): CostRepository

    abstract fun vehicles(): VehicleRepository

    abstract fun state(): SyncStateRepository

    // ---------------- trips ----------------

    @Test
    fun `upsert counts only new ids`() = runTest {
        val repo = trips()

        assertEquals(2, repo.upsertAll(listOf(tripRecord("t1", T0), tripRecord("t2", T0.plusSeconds(60)))))
        assertEquals(1, repo.upsertAll(listOf(tripRecord("t1", T0), tripRecord("t3", T0.plusSeconds(120)))))
        assertEquals(3, repo.count())
    }

    @Test
    fun `upsert replaces the row of an existing id`() = runTest {
        val repo = trips()
        repo.upsertAll(listOf(tripRecord("t1", T0, vin = VIN_A)))

        repo.upsertAll(listOf(tripRecord("t1", T0, vin = VIN_B).copy(startLat = 12.5)))

        assertEquals(1, repo.count())
        assertEquals(VIN_B, requireNotNull(repo.byId("t1")).vin)
        assertEquals(12.5, requireNotNull(repo.byId("t1")).startLat)
    }

    @Test
    fun `trips are separated per vin`() = runTest {
        val repo = trips()
        repo.upsertAll(listOf(tripRecord("t1", T0, vin = VIN_A), tripRecord("t2", T0, vin = VIN_B)))

        assertEquals(listOf("t1"), repo.all(VIN_A).map { it.id })
        assertEquals(listOf("t2"), repo.all(VIN_B).map { it.id })
        assertEquals(2, repo.count())
    }

    @Test
    fun `all is oldest first and allSince is newest first`() = runTest {
        val repo = trips()
        repo.upsertAll(
            listOf(
                tripRecord("t3", T0.plusSeconds(1_200)),
                tripRecord("t1", T0),
                tripRecord("t2", T0.plusSeconds(600)),
            ),
        )

        assertEquals(listOf("t1", "t2", "t3"), repo.all(VIN_A).map { it.id })
        assertEquals(listOf("t3", "t2"), repo.allSince(VIN_A, T0.plusSeconds(600)).map { it.id })
    }

    @Test
    fun `idsBetween is inclusive at both ends`() = runTest {
        val repo = trips()
        repo.upsertAll(
            listOf(
                tripRecord("before", T0.minusSeconds(1)),
                tripRecord("from", T0),
                tripRecord("inside", T0.plusSeconds(60)),
                tripRecord("to", T0.plusSeconds(120)),
                tripRecord("after", T0.plusSeconds(121)),
            ),
        )

        val ids = repo.idsBetween(VIN_A, T0, T0.plusSeconds(120))

        assertEquals(setOf("from", "inside", "to"), ids)
    }

    // ---------------- refuels ----------------

    @Test
    fun `addIfAbsent ignores a duplicate refuel id`() = runTest {
        val repo = refuels()

        assertTrue(repo.addIfAbsent(refuel("refuel-1", T0), VIN_A))
        assertFalse(repo.addIfAbsent(refuel("refuel-1", T0), VIN_A))
        assertEquals(1, repo.count())
    }

    @Test
    fun `update keeps the vehicle`() = runTest {
        val repo = refuels()
        repo.addIfAbsent(refuel("refuel-1", T0, RefuelStatus.PENDING), VIN_A)

        repo.update(refuel("refuel-1", T0, RefuelStatus.CONFIRMED))

        assertEquals(RefuelStatus.CONFIRMED, requireNotNull(repo.byId("refuel-1")).status)
        assertEquals(listOf("refuel-1"), repo.confirmedUpTo(T0, VIN_A).map { it.id })
    }

    @Test
    fun `confirmedUpTo is inclusive and ordered by ts then id`() = runTest {
        val repo = refuels()
        repo.addIfAbsent(refuel("r-late", T0.plusSeconds(60)), VIN_A)
        repo.addIfAbsent(refuel("r-b", T0), VIN_A)
        repo.addIfAbsent(refuel("r-a", T0), VIN_A)
        repo.addIfAbsent(refuel("r-pending", T0, RefuelStatus.PENDING), VIN_A)
        repo.addIfAbsent(refuel("r-other-vin", T0), VIN_B)

        assertEquals(listOf("r-a", "r-b"), repo.confirmedUpTo(T0, VIN_A).map { it.id })
        assertEquals(listOf("r-a", "r-b", "r-late"), repo.confirmedUpTo(T0.plusSeconds(60), VIN_A).map { it.id })
    }

    @Test
    fun `pending is newest first and vin filtered`() = runTest {
        val repo = refuels()
        repo.addIfAbsent(refuel("p-old", T0, RefuelStatus.PENDING), VIN_A)
        repo.addIfAbsent(refuel("p-new", T0.plusSeconds(60), RefuelStatus.PENDING), VIN_A)
        repo.addIfAbsent(refuel("p-other", T0, RefuelStatus.PENDING), VIN_B)

        assertEquals(listOf("p-new", "p-old"), repo.pending(VIN_A).map { it.id })
        assertEquals(3, repo.pending().size)
    }

    // ---------------- readings ----------------

    @Test
    fun `reading of the same vin and ts is stored once`() = runTest {
        val repo = readings()

        assertTrue(repo.addIfAbsent(reading(T0, 50), VIN_A))
        assertFalse(repo.addIfAbsent(reading(T0, 50), VIN_A))
        assertTrue(repo.addIfAbsent(reading(T0, 50), VIN_B))
        assertEquals(2, repo.count())
    }

    @Test
    fun `latest is the newest reading of that vin`() = runTest {
        val repo = readings()
        repo.addIfAbsent(reading(T0, 30), VIN_A)
        repo.addIfAbsent(reading(T0.plusSeconds(60), 40), VIN_A)
        repo.addIfAbsent(reading(T0.plusSeconds(120), 90), VIN_B)

        assertEquals(40, requireNotNull(repo.latest(VIN_A)).levelPct)
        assertEquals(90, requireNotNull(repo.latest(VIN_B)).levelPct)
        assertNull(repo.latest("VINCCCC0000000001"))
    }

    @Test
    fun `since is ascending and vin filtered`() = runTest {
        val repo = readings()
        repo.addIfAbsent(reading(T0.plusSeconds(120), 20), VIN_A)
        repo.addIfAbsent(reading(T0, 30), VIN_A)
        repo.addIfAbsent(reading(T0.plusSeconds(60), 40), VIN_B)

        assertEquals(listOf(T0, T0.plusSeconds(120)), repo.since(T0, VIN_A).map { it.ts })
    }

    // ---------------- costs ----------------

    @Test
    fun `costs are replaced per trip id`() = runTest {
        val repo = costs()

        repo.saveAll(listOf(cost("t1", CostSource.NONE)))
        repo.saveAll(listOf(cost("t1", CostSource.REFUEL)))
        repo.saveAll(listOf(cost("t2")))

        assertEquals(2, repo.count())
        assertEquals(CostSource.REFUEL, requireNotNull(repo.forTrip("t1")).source)
        assertEquals(2, repo.all().size)
        assertNull(repo.forTrip("missing"))
    }

    // ---------------- vehicles and state ----------------

    @Test
    fun `vehicle upsert keeps firstSeen`() = runTest {
        val repo = vehicles()
        val first = VehicleRecord(VIN_A, "T", "Corolla", "HEV", false, T0, T0)

        repo.upsert(first)
        repo.upsert(first.copy(modelName = "Corolla Hybrid", lastSeen = T0.plusSeconds(3_600)))

        val stored = assertNotNull(repo.byVin(VIN_A))
        assertEquals(T0, stored.firstSeen)
        assertEquals(T0.plusSeconds(3_600), stored.lastSeen)
        assertEquals("Corolla Hybrid", stored.modelName)
        assertEquals(1, repo.count())
    }

    @Test
    fun `sync state round trips`() = runTest {
        val repo = state()

        assertEquals(SyncState(), repo.get())

        val saved = SyncState(
            vin = VIN_A,
            watermarkEpochDay = 20_758L,
            lastAttemptAt = T0,
            lastSuccessAt = T0,
            lastErrorAt = T0.plusSeconds(60),
            lastErrorKind = SyncErrorKind.API,
            lastErrorMessage = "Toyota API request failed (HTTP 429) REDACTED",
        )
        repo.save(saved)

        assertEquals(saved, repo.get())
        assertTrue(repo.get().isStale)
        assertTrue(!repo.get().needsLogin)
    }
}

/** Same contract on the in-memory repositories used by [pl.lk.toyotacompanion.core.sync.SyncEngineTest]. */
class InMemoryRepositoriesTest : RepositoryContractTest() {
    private val stores = InMemoryStores()

    override fun trips() = stores.trips

    override fun refuels() = stores.refuels

    override fun readings() = stores.readings

    override fun costs() = stores.costs

    override fun vehicles() = stores.vehicles

    override fun state() = stores.state
}
