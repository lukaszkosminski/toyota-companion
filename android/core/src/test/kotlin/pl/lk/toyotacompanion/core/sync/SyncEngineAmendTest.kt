@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.sync

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import pl.lk.toyotacompanion.core.data.InMemoryStores
import pl.lk.toyotacompanion.core.data.SyncConfig
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip

private const val VIN = "VINAAA111"
private const val OTHER_VIN = "VINBBB222"

/** The day the amended refuel happened; everything else is placed around it. */
private val DAY: Instant = Instant.parse("2026-10-20T12:00:00Z")

private fun days(before: Long): Instant = DAY.minusSeconds(before * 86_400L)

private fun assertBd(expected: String, actual: BigDecimal?, label: String) {
    val e = BigDecimal(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

/**
 * The first tank of the story: priced, receipt liters, nothing to correct. Its price is
 * what the tank carries before the second refuel is mixed into it (ADR-005).
 */
private fun anchorRefuel(ts: Instant = days(10)): Refuel = Refuel(
    id = "anchor",
    ts = ts,
    odometerKm = 9_000.0,
    levelBefore = 20,
    levelAfter = 90,
    liters = BigDecimal("30.000"),
    pricePerLiter = BigDecimal("6.000"),
    totalCost = BigDecimal("180.00"),
    status = RefuelStatus.CONFIRMED,
    litersSource = LitersSource.RECEIPT,
    filledToFull = true,
)

/**
 * The confirmed refuel the correction is for: the user gave the price only, so the
 * liters are unknown and the diary has nothing to compare (PRD F9). Levels 80 -> 90
 * keep the sensor estimate small (4.3 l of a 43 l tank), which is what the corrected
 * 30 l replaces in the weighted tank price.
 */
private fun priceOnlyRefuel(
    id: String = "r2",
    ts: Instant = days(1),
    odometerKm: Double = 9_800.0,
): Refuel =
    Refuel(
        id = id,
        ts = ts,
        odometerKm = odometerKm,
        levelBefore = 80,
        levelAfter = 90,
        liters = null,
        pricePerLiter = BigDecimal("6.190"),
        totalCost = null,
        status = RefuelStatus.CONFIRMED,
    )

private fun pendingRefuel(id: String, ts: Instant = days(1)): Refuel = Refuel(
    id = id,
    ts = ts,
    odometerKm = 9_800.0,
    levelBefore = 80,
    levelAfter = 90,
    liters = null,
    pricePerLiter = null,
    totalCost = null,
    status = RefuelStatus.PENDING,
)

private fun tripOf(id: String, start: Instant, vin: String = VIN): TripRecord = TripRecord(
    trip = Trip(
        id = id,
        start = start,
        end = start.plusSeconds(1_800),
        distanceKm = 14.6,
        fuelL = BigDecimal("0.600"),
        evDistanceKm = null,
    ),
    vin = vin,
)

/**
 * Correcting the liters of a confirmed refuel (PRD F9, docs/SPEC.md §15.3): the entry
 * keeps its price unless the receipt amount is given, and the liters change the price
 * in the tank, so the costs are recomputed exactly like for a price (docs/SPEC.md §6).
 */
class SyncEngineAmendTest {

    private class Ctx(val stores: InMemoryStores, val engine: SyncEngine)

    private fun ctx(): Ctx {
        val stores = InMemoryStores()
        val engine = SyncEngine(
            gateway = FakeToyotaGateway(),
            vehicles = stores.vehicles,
            trips = stores.trips,
            refuels = stores.refuels,
            readings = stores.readings,
            costs = stores.costs,
            state = stores.state,
            config = SyncConfig(),
            clock = { DAY },
        )
        return Ctx(stores, engine)
    }

    /** Anchor tank, the refuel to correct and one trip before and one after it. */
    private suspend fun Ctx.seedConfirmedPair(vin: String = VIN) {
        stores.state.save(SyncState(vin = vin))
        stores.refuels.addIfAbsent(anchorRefuel(), vin)
        stores.refuels.addIfAbsent(priceOnlyRefuel(), vin)
        stores.trips.upsertAll(listOf(tripOf("t-old", days(5), vin = vin), tripOf("t-new", DAY, vin = vin)))
    }

    @Test
    fun `amend_of_a_price_only_confirmation_stores_receipt_liters`() = runTest {
        val c = ctx()
        c.seedConfirmedPair()

        val done = assertIs<RefuelChange.Done>(c.engine.amendRefuel("r2", BigDecimal("30.000"), filledToFull = true))

        // Without an amount the stored price stays and the total is price x liters.
        assertBd("6.190", done.refuel.pricePerLiter, "price_per_liter")
        assertBd("185.70", done.refuel.totalCost, "total_cost")
        val stored = requireNotNull(c.stores.refuels.byId("r2"))
        assertBd("30.000", stored.liters, "liters")
        assertBd("6.190", stored.pricePerLiter, "stored price")
        assertBd("185.70", stored.totalCost, "stored total")
        assertEquals(LitersSource.RECEIPT, stored.litersSource)
        assertEquals(true, stored.filledToFull)
        assertEquals(RefuelStatus.CONFIRMED, stored.status)
        // The correction touches the money only, never the identity of the event.
        assertEquals(priceOnlyRefuel().ts, stored.ts)
        assertEquals(priceOnlyRefuel().odometerKm, stored.odometerKm)
        assertEquals(80, stored.levelBefore)
        assertEquals(90, stored.levelAfter)
    }

    @Test
    fun `amend_with_the_receipt_amount_reprices_the_liters`() = runTest {
        val c = ctx()
        c.seedConfirmedPair()

        val done = assertIs<RefuelChange.Done>(
            c.engine.amendRefuel("r2", BigDecimal("30.000"), BigDecimal("186.00"), filledToFull = true),
        )

        // Liters + amount is the receipt pair: 186.00 / 30.000 = 6.200 zl/l.
        assertBd("6.200", done.refuel.pricePerLiter, "price_per_liter")
        assertBd("186.00", done.refuel.totalCost, "total_cost")
        assertBd("30.000", requireNotNull(c.stores.refuels.byId("r2")).liters, "liters")
        assertEquals(LitersSource.RECEIPT, requireNotNull(c.stores.refuels.byId("r2")).litersSource)
    }

    @Test
    fun `amend_can_clear_the_full_tank_mark`() = runTest {
        val c = ctx()
        c.seedConfirmedPair()
        c.engine.amendRefuel("r2", BigDecimal("30.000"), filledToFull = true)

        assertIs<RefuelChange.Done>(c.engine.amendRefuel("r2", BigDecimal("29.500"), filledToFull = false))

        // "Do pełna" off again: an interval must not stay open on a wrong mark.
        assertEquals(false, requireNotNull(c.stores.refuels.byId("r2")).filledToFull)
        assertBd("29.500", requireNotNull(c.stores.refuels.byId("r2")).liters, "liters")
    }

    @Test
    fun `amend_recomputes_the_costs_and_the_tank_price`() = runTest {
        val c = ctx()
        c.seedConfirmedPair()
        assertIs<CostRecompute.Done>(c.engine.recomputeCostsNow())

        // Before the correction the second tank is the 4.3 l the sensor estimated:
        // (34.4 l x 6.000 + 4.3 l x 6.190) / 38.7 l = 6.021 zl/l.
        val before = requireNotNull(c.stores.costs.forTrip("t-new"))
        assertEquals(CostSource.REFUEL, before.source)
        assertBd("6.021", before.pricePerLiter, "tank price before")
        assertBd("3.61", before.costPln, "cost before")

        val done = assertIs<RefuelChange.Done>(c.engine.amendRefuel("r2", BigDecimal("30.000"), filledToFull = true))
        assertEquals(2, done.tripsCosted, "every trip of the vehicle is recounted")
        assertEquals(2, c.stores.costs.count())

        // With 30 l the mix is (34.4 x 6.000 + 30 x 6.190) / 64.4 = 6.089 zl/l.
        val after = requireNotNull(c.stores.costs.forTrip("t-new"))
        assertBd("6.089", after.pricePerLiter, "tank price after")
        assertBd("3.65", after.costPln, "cost after")
        // The trip before the corrected refuel keeps the price it had.
        assertBd("6.000", requireNotNull(c.stores.costs.forTrip("t-old")).pricePerLiter, "older trip")
    }

    @Test
    fun `amend_refuses_a_pending_entry_and_writes_nothing`() = runTest {
        val c = ctx()
        c.stores.state.save(SyncState(vin = VIN))
        c.stores.refuels.addIfAbsent(pendingRefuel("p1"), VIN)
        val before = requireNotNull(c.stores.refuels.byId("p1"))

        assertIs<RefuelChange.NotConfirmed>(
            c.engine.amendRefuel("p1", BigDecimal("30.000"), filledToFull = true),
        )

        assertEquals(before, requireNotNull(c.stores.refuels.byId("p1")), "the row is untouched")
        assertEquals(0, c.stores.costs.count(), "nothing was recomputed either")
    }

    @Test
    fun `amend_refuses_a_dismissed_entry`() = runTest {
        val c = ctx()
        c.stores.state.save(SyncState(vin = VIN))
        c.stores.refuels.addIfAbsent(pendingRefuel("d1").copy(status = RefuelStatus.DISMISSED), VIN)
        val before = requireNotNull(c.stores.refuels.byId("d1"))

        assertIs<RefuelChange.NotConfirmed>(
            c.engine.amendRefuel("d1", BigDecimal("30.000"), BigDecimal("186.00"), filledToFull = true),
        )

        assertEquals(before, requireNotNull(c.stores.refuels.byId("d1")))
    }

    @Test
    fun `amend_of_a_confirmed_refuel_of_another_vehicle_is_not_found`() = runTest {
        val c = ctx()
        c.stores.state.save(SyncState(vin = VIN))
        c.stores.refuels.addIfAbsent(priceOnlyRefuel("other"), OTHER_VIN)
        val before = requireNotNull(c.stores.refuels.byId("other"))

        // The row exists and is confirmed, but this screen never shows another car.
        assertIs<RefuelChange.NotFound>(
            c.engine.amendRefuel("other", BigDecimal("30.000"), filledToFull = true),
        )

        assertEquals(before, requireNotNull(c.stores.refuels.byId("other")), "nothing was written")
    }

    @Test
    fun `amend_without_a_selected_vehicle_is_no_vehicle`() = runTest {
        val c = ctx()
        c.stores.refuels.addIfAbsent(priceOnlyRefuel(), VIN)

        assertIs<RefuelChange.NoVehicle>(
            c.engine.amendRefuel("r2", BigDecimal("30.000"), filledToFull = true),
        )
        assertNull(c.stores.state.get().vin)
        assertBd("6.190", requireNotNull(c.stores.refuels.byId("r2")).pricePerLiter, "price kept")
    }

    @Test
    fun `amend_with_zero_liters_is_invalid_and_writes_nothing`() = runTest {
        val c = ctx()
        c.seedConfirmedPair()
        val before = requireNotNull(c.stores.refuels.byId("r2"))

        assertIs<RefuelChange.Invalid>(
            c.engine.amendRefuel("r2", BigDecimal("0.000"), filledToFull = true),
        )

        assertEquals(before, requireNotNull(c.stores.refuels.byId("r2")), "the domain refused it, nothing was written")
        assertEquals(0, c.stores.costs.count())
    }

    @Test
    fun `amend_of_an_unknown_id_is_not_found`() = runTest {
        val c = ctx()
        c.seedConfirmedPair()

        assertIs<RefuelChange.NotFound>(
            c.engine.amendRefuel("nope", BigDecimal("30.000"), filledToFull = true),
        )
    }

    // ---------------- regression of the price path (docs/SPEC.md §11.1) ----------------

    @Test
    fun `confirming_a_price_on_a_pending_entry_still_works`() = runTest {
        val c = ctx()
        c.stores.state.save(SyncState(vin = VIN))
        c.stores.refuels.addIfAbsent(pendingRefuel("p1"), VIN)
        c.stores.trips.upsertAll(listOf(tripOf("t1", DAY.plusSeconds(3_600))))

        val done = assertIs<RefuelChange.Done>(c.engine.confirmRefuel("p1", pricePerLiter = BigDecimal("6.19")))

        assertEquals(1, done.tripsCosted)
        val stored = requireNotNull(c.stores.refuels.byId("p1"))
        assertEquals(RefuelStatus.CONFIRMED, stored.status)
        assertBd("6.190", stored.pricePerLiter, "price_per_liter")
        // A price alone still says nothing about liters: that is what the correction is for.
        assertNull(stored.liters)
        assertNull(stored.litersSource)
        assertBd("6.190", requireNotNull(c.stores.costs.forTrip("t1")).pricePerLiter, "tank price")
    }

    @Test
    fun `confirming_a_price_for_a_pending_refuel_of_another_vehicle_is_still_not_found`() = runTest {
        val c = ctx()
        c.stores.state.save(SyncState(vin = VIN))
        c.stores.refuels.addIfAbsent(pendingRefuel("other"), OTHER_VIN)

        assertIs<RefuelChange.NotFound>(c.engine.confirmRefuel("other", pricePerLiter = BigDecimal("6.19")))
        assertEquals(
            RefuelStatus.PENDING,
            requireNotNull(c.stores.refuels.byId("other")).status,
            "the old guard still refuses another car's entry",
        )
    }

    @Test
    fun `confirming_a_second_time_is_not_pending_while_amend_is_not_confirmed`() = runTest {
        val c = ctx()
        c.seedConfirmedPair()

        // The two guards keep their own meaning on the shared write path: the price form
        // and the refusal still answer NotPending, only the correction answers NotConfirmed.
        assertIs<RefuelChange.NotPending>(c.engine.confirmRefuel("r2", pricePerLiter = BigDecimal("6.19")))
        assertIs<RefuelChange.NotPending>(c.engine.dismissRefuel("r2"))
    }
}
