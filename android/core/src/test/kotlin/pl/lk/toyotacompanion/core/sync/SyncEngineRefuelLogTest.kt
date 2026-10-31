@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.sync

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import pl.lk.toyotacompanion.core.data.InMemoryStores
import pl.lk.toyotacompanion.core.data.SyncConfig
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.EconomyStatus
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip

private const val VIN = "VINAAA111"
private const val OTHER_VIN = "VINBBB222"

private val DAY_1: Instant = Instant.parse("2026-10-20T12:00:00Z")

private fun assertBd(expected: String, actual: BigDecimal?, label: String) {
    val e = BigDecimal(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

/** A refuel the confirm form leaves behind: confirmed, receipt liters, filled to full. */
private fun fullRefuel(
    id: String,
    ts: Instant,
    liters: String = "30.000",
    odometerKm: Double = 1000.0,
    status: RefuelStatus = RefuelStatus.CONFIRMED,
    filledToFull: Boolean? = true,
): Refuel = Refuel(
    id = id,
    ts = ts,
    odometerKm = odometerKm,
    levelBefore = 20,
    levelAfter = 90,
    liters = BigDecimal(liters),
    pricePerLiter = BigDecimal("6.190"),
    totalCost = BigDecimal("185.70"),
    status = status,
    litersSource = LitersSource.RECEIPT,
    filledToFull = filledToFull,
)

/** A trip of the given car; the log window is decided by its start alone. */
private fun tripOf(
    id: String,
    start: Instant,
    km: Double = 200.0,
    fuel: String = "9.000",
    vin: String = VIN,
): TripRecord = TripRecord(
    trip = Trip(
        id = id,
        start = start,
        end = start.plusSeconds(7_200),
        distanceKm = km,
        fuelL = BigDecimal(fuel),
        evDistanceKm = null,
    ),
    vin = vin,
)

/**
 * The read-only view of the refuel log (PRD F9, docs/SPEC.md §15): no gateway call,
 * no mutex, and the domain must only ever see the trips and refuels of one car.
 */
class SyncEngineRefuelLogTest {

    private class Ctx(
        val gateway: FakeToyotaGateway,
        val stores: InMemoryStores,
        val engine: SyncEngine,
    )

    private fun ctx(start: Instant = DAY_1): Ctx {
        val stores = InMemoryStores()
        val gateway = FakeToyotaGateway()
        val engine = SyncEngine(
            gateway = gateway,
            vehicles = stores.vehicles,
            trips = stores.trips,
            refuels = stores.refuels,
            readings = stores.readings,
            costs = stores.costs,
            state = stores.state,
            config = SyncConfig(),
            clock = { start },
        )
        return Ctx(gateway, stores, engine)
    }

    /** One full tank at DAY_1, the next ten days later, 600 km of trips between them. */
    private suspend fun Ctx.seedOneVehicle(vin: String = VIN, tripKm: Double = 200.0) {
        stores.state.save(SyncState(vin = vin))
        stores.refuels.addIfAbsent(fullRefuel("a", DAY_1, odometerKm = 1000.0), vin)
        stores.refuels.addIfAbsent(fullRefuel("b", DAY_1.plusSeconds(10 * 86_400), odometerKm = 1600.0), vin)
        for (day in 1..3) {
            stores.trips.upsertAll(
                listOf(tripOf("t$vin$day", DAY_1.plusSeconds(day * 86_400L), km = tripKm, vin = vin)),
            )
        }
    }

    @Test
    fun `no selected vin gives an empty log`() = runTest {
        val c = ctx()
        // The data is in the stores; only the selection is missing (docs/SPEC.md §15.1).
        c.stores.refuels.addIfAbsent(fullRefuel("a", DAY_1), VIN)
        c.stores.trips.upsertAll(listOf(tripOf("t1", DAY_1.plusSeconds(86_400))))

        assertEquals(RefuelLog.EMPTY, c.engine.refuelLog())
    }

    @Test
    fun `the log of the selected vehicle ignores the other one`() = runTest {
        val c = ctx()
        c.seedOneVehicle(vin = VIN, tripKm = 200.0)
        // The second car tanked twice and drove 500 km per trip at the very same moments.
        c.stores.refuels.addIfAbsent(fullRefuel("other-a", DAY_1, odometerKm = 500.0), OTHER_VIN)
        c.stores.refuels.addIfAbsent(
            fullRefuel("other-b", DAY_1.plusSeconds(10 * 86_400), odometerKm = 2500.0),
            OTHER_VIN,
        )
        for (day in 1..3) {
            c.stores.trips.upsertAll(
                listOf(tripOf("o$day", DAY_1.plusSeconds(day * 86_400L), km = 500.0, vin = OTHER_VIN)),
            )
        }

        val log = c.engine.refuelLog()

        assertEquals(listOf("b", "a"), log.refuels.map { it.id }, "only the selected car, newest first")
        assertEquals(2, log.intervals.size)
        val closed = log.intervals[0]
        // 600 km, not 2100: the intervals come from this vehicle's trips alone.
        assertBd("600.0", closed.distanceKm, "distance_km")
        assertBd("27.000", closed.computerLiters, "computer_liters")
        assertEquals(listOf("a", "b"), listOf(closed.startRefuelId, closed.endRefuelId))
    }

    @Test
    fun `only confirmed refuels are listed and ties fall back to id`() = runTest {
        val c = ctx()
        c.stores.state.save(SyncState(vin = VIN))
        c.stores.refuels.addIfAbsent(fullRefuel("z", DAY_1, filledToFull = false), VIN)
        c.stores.refuels.addIfAbsent(fullRefuel("a", DAY_1, filledToFull = false), VIN)
        c.stores.refuels.addIfAbsent(
            fullRefuel("m", DAY_1.plusSeconds(3 * 86_400), filledToFull = false),
            VIN,
        )
        c.stores.refuels.addIfAbsent(
            fullRefuel("waiting", DAY_1.plusSeconds(2 * 86_400), status = RefuelStatus.PENDING),
            VIN,
        )
        c.stores.refuels.addIfAbsent(
            fullRefuel("gone", DAY_1.plusSeconds(4 * 86_400), status = RefuelStatus.DISMISSED),
            VIN,
        )

        val log = c.engine.refuelLog()

        // Newest first; the two refuels of the same instant fall back to the id, and
        // the entry waiting for a price and the dismissed one are not listed at all.
        assertEquals(listOf("m", "a", "z"), log.refuels.map { it.id })
        // Nothing is filled to full here, so no interval opens (docs/SPEC.md §15.1).
        assertTrue(log.intervals.isEmpty(), "without filled_to_full there is no interval")
    }

    @Test
    fun `a closed interval carries the compared economy`() = runTest {
        val c = ctx()
        c.seedOneVehicle()

        val log = c.engine.refuelLog()
        assertEquals(2, log.intervals.size)
        val closed = log.intervals[0]
        val open = log.intervals[1]

        assertEquals(EconomyStatus.OK, closed.status)
        assertEquals(3, closed.trips)
        assertBd("600.0", closed.distanceKm, "distance_km")
        assertBd("27.000", closed.computerLiters, "computer_liters")
        assertBd("30.000", closed.actualLiters, "actual_liters")
        assertBd("4.50", closed.computerLPer100Km, "computer_l_per_100km")
        assertBd("5.00", closed.actualLPer100Km, "actual_l_per_100km")
        assertBd("11.1", closed.differencePct, "difference_pct")
        assertBd("600.0", closed.odometerKm, "odometer_km")

        assertEquals(EconomyStatus.OPEN, open.status)
        assertEquals("b", open.startRefuelId)
        assertEquals(null, open.endRefuelId)
    }

    @Test
    fun `the log is a read that never touches the gateway`() = runTest {
        val c = ctx()
        c.seedOneVehicle()

        c.engine.refuelLog()

        // A read of the archive like trips(): no network, no mutex (docs/SPEC.md §15.1).
        assertEquals(0, c.gateway.vehiclesCalls)
        assertEquals(0, c.gateway.telemetryCalls)
        assertEquals(0, c.gateway.tripsCalls)
    }
}
