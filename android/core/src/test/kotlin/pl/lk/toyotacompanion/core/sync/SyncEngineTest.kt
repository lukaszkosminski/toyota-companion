@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.sync

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import pl.lk.toyotacompanion.core.data.CostRepository
import pl.lk.toyotacompanion.core.data.InMemoryCostRepository
import pl.lk.toyotacompanion.core.data.InMemoryStores
import pl.lk.toyotacompanion.core.data.SyncConfig
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException
import pl.lk.toyotacompanion.core.toyota.ToyotaDataException
import pl.lk.toyotacompanion.core.toyota.ToyotaInvalidUsernameException
import pl.lk.toyotacompanion.core.toyota.ToyotaNetworkException

private const val VIN = "VINAAA111"
private const val OTHER_VIN = "VINBBB222"

private val DAY_1: Instant = Instant.parse("2026-10-20T12:00:00Z")
private val DAY_2: Instant = Instant.parse("2026-10-21T12:00:00Z")

private fun utcDate(instant: Instant): LocalDate = LocalDate.ofInstant(instant, ZoneOffset.UTC)

private fun vehicleRecord(vin: String = VIN): VehicleRecord = VehicleRecord(
    vin = vin,
    brand = "TOYOTA",
    modelName = "Corolla Hybrid",
    fuelType = "HEV",
    evVehicle = false,
    firstSeen = DAY_1,
    lastSeen = DAY_1,
)

private fun reading(ts: Instant, levelPct: Int, odometerKm: Double = 10_000.0): FuelReading =
    FuelReading(ts = ts, levelPct = levelPct, odometerKm = odometerKm)

private fun tripRecord(id: String, start: Instant, fuelL: String = "0.60", vin: String = VIN): TripRecord =
    TripRecord(
        trip = Trip(
            id = id,
            start = start,
            end = start.plusSeconds(1_800),
            distanceKm = 14.6,
            fuelL = BigDecimal(fuelL),
            evDistanceKm = null,
        ),
        vin = vin,
    )

/** Confirmed refuel with receipt liters — the shape ADR-005 needs to price a tank. */
private fun confirmedRefuel(
    ts: Instant = DAY_1.minusSeconds(86_400),
    price: String = "6.19",
    liters: String = "25.800",
): Refuel = Refuel(
    id = "refuel-$ts",
    ts = ts,
    odometerKm = 9_900.0,
    levelBefore = 20,
    levelAfter = 80,
    liters = BigDecimal(liters),
    pricePerLiter = BigDecimal(price),
    totalCost = BigDecimal(price).multiply(BigDecimal(liters), java.math.MathContext(28)),
    status = RefuelStatus.CONFIRMED,
    litersSource = LitersSource.RECEIPT,
)

/** Refuel as `detect_refuel` leaves it: no price yet, waiting for the user (PRD F1, F2). */
private fun pendingRefuel(id: String, ts: Instant): Refuel = Refuel(
    id = id,
    ts = ts,
    odometerKm = 9_900.0,
    levelBefore = 20,
    levelAfter = 80,
    liters = null,
    pricePerLiter = null,
    totalCost = null,
    status = RefuelStatus.PENDING,
)

/**
 * The synchronization pipeline against in-memory repositories and a fake Toyota
 * client: offline, deterministic, injected clock (docs/SPEC.md §8, docs/DOD.md).
 */
class SyncEngineTest {

    private class Ctx(
        val gateway: FakeToyotaGateway,
        val stores: InMemoryStores,
        val clock: FakeClock,
        val engine: SyncEngine,
    )

    private fun ctx(
        gateway: FakeToyotaGateway = FakeToyotaGateway(fleet = listOf(vehicleRecord())),
        stores: InMemoryStores = InMemoryStores(),
        start: Instant = DAY_1,
        config: SyncConfig = SyncConfig(),
        hasCredentials: suspend () -> Boolean = { true },
        defaultPriceProvider: suspend () -> BigDecimal? = { config.defaultPrice },
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
            config = config,
            clock = { clock() },
            hasCredentials = hasCredentials,
            defaultPriceProvider = defaultPriceProvider,
        )
        return Ctx(gateway, stores, clock, engine)
    }

    private fun assertOk(outcome: SyncOutcome): SyncOutcome.Ok = assertIs<SyncOutcome.Ok>(outcome)

    private fun assertFailed(outcome: SyncOutcome, kind: SyncErrorKind): SyncOutcome.Failed {
        val failed = assertIs<SyncOutcome.Failed>(outcome)
        assertEquals(kind, failed.error.kind, "got ${failed.error}")
        return failed
    }

    // ---------------- happy path ----------------

    @Test
    fun `first_sync_stores_vehicle_reading_and_trips`() = runTest {
        val c = ctx(
            FakeToyotaGateway(
                fleet = listOf(vehicleRecord()),
                telemetry = reading(DAY_1, 40),
                tripsProvider = { _, _, _ -> listOf(tripRecord("t1", DAY_1.minusSeconds(3_600))) },
            ),
        )

        val ok = assertOk(c.engine.fullSync())

        assertEquals(1, ok.tripsAdded)
        assertEquals(1, ok.readingsAdded)
        assertEquals(0, ok.refuelsDetected)
        assertTrue(!ok.telemetryFailed)
        assertEquals(1, c.stores.readings.count())
        assertEquals(1, c.stores.trips.count())
        assertEquals(listOf(VIN), c.stores.vehicles.all().map { it.vin })
        assertTrue(!c.stores.state.get().isStale)
        assertEquals(VIN, c.stores.state.get().vin)
    }

    @Test
    fun `first_sync_window_is_365_days`() = runTest {
        val c = ctx()
        assertOk(c.engine.fullSync())

        val today = utcDate(DAY_1)
        assertEquals(listOf(today.minusDays(365) to today), c.gateway.tripWindows)
    }

    @Test
    fun `next_sync_window_is_watermark_minus_two_days`() = runTest {
        val c = ctx()
        assertOk(c.engine.fullSync())
        assertEquals(utcDate(DAY_1).toEpochDay(), c.stores.state.get().watermarkEpochDay)

        c.clock.at(DAY_2)
        assertOk(c.engine.fullSync())

        assertEquals(2, c.gateway.tripWindows.size)
        assertEquals(utcDate(DAY_1).minusDays(2) to utcDate(DAY_2), c.gateway.tripWindows[1])
        assertEquals(utcDate(DAY_2).toEpochDay(), c.stores.state.get().watermarkEpochDay)
    }

    @Test
    fun `delayed_trip_picked_up_by_two_day_overlap`() = runTest {
        val gateway = FakeToyotaGateway(
            fleet = listOf(vehicleRecord()),
            telemetry = reading(DAY_1, 40),
            tripsProvider = { _, _, _ -> emptyList() },
        )
        val c = ctx(gateway)
        assertOk(c.engine.fullSync())
        assertEquals(0, c.stores.trips.count())

        gateway.tripsProvider = { _, _, _ -> listOf(tripRecord("t-late", DAY_1.minusSeconds(7_200))) }
        c.clock.at(DAY_2)

        assertEquals(1, assertOk(c.engine.fullSync()).tripsAdded)
        assertEquals(1, c.stores.trips.count())
    }

    @Test
    fun `trips_deduplicated_by_id`() = runTest {
        val same = tripRecord("t1", DAY_1.minusSeconds(3_600))
        val c = ctx(FakeToyotaGateway(fleet = listOf(vehicleRecord()), tripsProvider = { _, _, _ -> listOf(same) }))

        assertEquals(1, assertOk(c.engine.fullSync()).tripsAdded)
        assertEquals(0, assertOk(c.engine.fullSync()).tripsAdded)
        assertEquals(1, c.stores.trips.count())
    }

    @Test
    fun `watermark_unchanged_after_trip_error`() = runTest {
        val c = ctx()
        assertOk(c.engine.fullSync())
        val watermark = requireNotNull(c.stores.state.get().watermarkEpochDay)

        c.gateway.tripsError = ToyotaApiException(403, "forbidden")
        c.clock.at(DAY_2)
        assertFailed(c.engine.fullSync(), SyncErrorKind.API)

        assertEquals(watermark, c.stores.state.get().watermarkEpochDay)
    }

    // ---------------- refuel detection (docs/SPEC.md §1, §8.3) ----------------

    @Test
    fun `refuel_detected_from_jump_of_8_pp`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))
        gateway.enqueueTelemetry(reading(DAY_1, 30))
        gateway.enqueueTelemetry(reading(DAY_2, 38))
        val c = ctx(gateway)

        assertOk(c.engine.fullSync())
        c.clock.at(DAY_2)
        assertEquals(1, assertOk(c.engine.fullSync()).refuelsDetected)

        val pending = c.stores.refuels.pending(VIN)
        assertEquals(1, pending.size)
        assertEquals("refuel-$DAY_2", pending.single().id)
        assertEquals(30, pending.single().levelBefore)
        assertEquals(38, pending.single().levelAfter)
        assertEquals(RefuelStatus.PENDING, pending.single().status)
        assertEquals(2, c.stores.readings.count())
    }

    @Test
    fun `no_refuel_below_threshold`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))
        gateway.enqueueTelemetry(reading(DAY_1, 30))
        gateway.enqueueTelemetry(reading(DAY_2, 37)) // 7 pp is below the 8 pp threshold
        val c = ctx(gateway)

        assertOk(c.engine.fullSync())
        c.clock.at(DAY_2)

        assertEquals(0, assertOk(c.engine.fullSync()).refuelsDetected)
        assertEquals(0, c.stores.refuels.count())
    }

    @Test
    fun `refuel_idempotent_on_second_sync`() = runTest {
        // The car sleeps: Toyota keeps reporting the very same telemetry.
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))
        gateway.enqueueTelemetry(reading(DAY_1, 30))
        gateway.enqueueTelemetry(reading(DAY_2, 95))
        val c = ctx(gateway)

        assertOk(c.engine.fullSync())
        c.clock.at(DAY_2)
        assertEquals(1, assertOk(c.engine.fullSync()).refuelsDetected)
        assertOk(c.engine.fullSync())
        assertOk(c.engine.fullSync())

        assertEquals(1, c.stores.refuels.count())
        assertEquals(2, c.stores.readings.count())
    }

    @Test
    fun `reading_of_older_ts_not_stored`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))
        gateway.enqueueTelemetry(reading(DAY_2, 30))
        gateway.enqueueTelemetry(reading(DAY_1, 99)) // stale payload with a big jump
        val c = ctx(gateway)

        assertOk(c.engine.fullSync())
        val ok = assertOk(c.engine.fullSync())

        assertEquals(0, ok.readingsAdded)
        assertEquals(0, ok.refuelsDetected)
        assertEquals(1, c.stores.readings.count())
        assertEquals(0, c.stores.refuels.count())
    }

    @Test
    fun `first_reading_stored_without_detection`() = runTest {
        val c = ctx(FakeToyotaGateway(fleet = listOf(vehicleRecord()), telemetry = reading(DAY_1, 100)))

        val ok = assertOk(c.engine.fullSync())

        assertEquals(1, ok.readingsAdded)
        assertEquals(0, ok.refuelsDetected)
        assertEquals(0, c.stores.refuels.count())
    }

    @Test
    fun `telemetry_missing_payload_stores_nothing_and_reports_failure`() = runTest {
        val c = ctx(
            FakeToyotaGateway(
                fleet = listOf(vehicleRecord()),
                telemetry = null,
                tripsProvider = { _, _, _ -> listOf(tripRecord("t1", DAY_1.minusSeconds(60))) },
            ),
        )

        val ok = assertOk(c.engine.fullSync())

        assertTrue(ok.telemetryFailed)
        assertEquals(0, c.stores.readings.count())
        assertEquals(0, c.stores.refuels.count())
        assertTrue(!ok.state.isStale) // the archive was still refreshed
    }

    @Test
    fun `telemetry_error_does_not_block_trips`() = runTest {
        val gateway = FakeToyotaGateway(
            fleet = listOf(vehicleRecord()),
            tripsProvider = { _, _, _ -> listOf(tripRecord("t1", DAY_1.minusSeconds(60))) },
        )
        gateway.telemetryError = ToyotaNetworkException("offline")
        val c = ctx(gateway)

        val ok = assertOk(c.engine.fullSync())

        assertTrue(ok.telemetryFailed)
        assertEquals(1, ok.tripsAdded)
        assertTrue(!ok.state.isStale)
    }

    // ---------------- failures keep local data (docs/ARCHITECTURE.md) ----------------

    @Test
    fun `api_error_keeps_local_data_and_marks_stale`() = runTest {
        val c = ctx(
            FakeToyotaGateway(
                fleet = listOf(vehicleRecord()),
                telemetry = reading(DAY_1, 40),
                tripsProvider = { _, _, _ -> listOf(tripRecord("t1", DAY_1.minusSeconds(3_600))) },
            ),
        )
        assertOk(c.engine.fullSync())

        c.gateway.tripsError = ToyotaApiException(429, "too many requests")
        c.clock.at(DAY_2)
        val failed = assertFailed(c.engine.fullSync(), SyncErrorKind.API)

        assertTrue(failed.state.isStale)
        assertTrue(failed.error.retryable)
        assertTrue(!c.engine.status().isFresh)
        assertEquals(1, c.stores.trips.count())
        assertEquals(1, c.stores.readings.count())
    }

    @Test
    fun `api_error_message_is_sanitized_and_has_no_vin`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))
        gateway.tripsError = ToyotaApiException(403, "denied for vin=$VIN access_token=SECRET123")
        val c = ctx(gateway)

        val failed = assertFailed(c.engine.fullSync(), SyncErrorKind.API)

        assertTrue(failed.error.message.contains("REDACTED"), failed.error.message)
        assertTrue(!failed.error.message.contains(VIN), "VIN leaked: ${failed.error.message}")
        assertTrue(!failed.error.message.contains("SECRET123"), "token leaked: ${failed.error.message}")
        assertTrue(!requireNotNull(failed.state.lastErrorMessage).contains(VIN))
    }

    @Test
    fun `network_error_is_retryable`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))
        gateway.vehiclesError = ToyotaNetworkException("no route to host")

        val failed = assertFailed(ctx(gateway).engine.fullSync(), SyncErrorKind.NETWORK)

        assertTrue(failed.error.retryable)
        assertTrue(!failed.state.needsLogin)
    }

    @Test
    fun `login_error_sets_needs_login`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))
        gateway.vehiclesError = ToyotaInvalidUsernameException("Authentication Failed. User Not Found.")
        val c = ctx(gateway)

        val failed = assertFailed(c.engine.fullSync(), SyncErrorKind.LOGIN)

        assertTrue(failed.state.needsLogin)
        assertTrue(!failed.error.retryable)
    }

    @Test
    fun `no_credentials_when_secrets_missing`() = runTest {
        val c = ctx(hasCredentials = { false })

        val failed = assertFailed(c.engine.fullSync(), SyncErrorKind.NO_CREDENTIALS)

        assertTrue(failed.state.needsLogin)
        assertEquals(0, c.gateway.vehiclesCalls)
        assertEquals(0, c.gateway.tripsCalls)
    }

    // ---------------- vehicles ----------------

    @Test
    fun `no_vehicle_when_account_returns_empty_list`() = runTest {
        val c = ctx(FakeToyotaGateway(fleet = emptyList()))

        assertFailed(c.engine.fullSync(), SyncErrorKind.NO_VEHICLE)

        assertEquals(0, c.gateway.tripsCalls)
        assertNull(c.stores.state.get().watermarkEpochDay)
    }

    @Test
    fun `two_vehicles_require_explicit_choice`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord(VIN), vehicleRecord(OTHER_VIN)))
        val c = ctx(gateway)

        assertFailed(c.engine.fullSync(), SyncErrorKind.NO_VEHICLE)
        assertEquals(0, gateway.tripsCalls)

        c.engine.selectVehicle(OTHER_VIN)
        val ok = assertOk(c.engine.fullSync())

        assertEquals(OTHER_VIN, ok.state.vin)
        assertEquals(1, gateway.tripWindows.size) // only the successful attempt fetched trips
        assertEquals(0, c.stores.trips.all(VIN).size)
    }

    @Test
    fun `vin_change_keeps_history_and_switches_selected_vin`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t-old", DAY_1.minusSeconds(86_400), vin = "VINOLD000")))
        stores.state.save(SyncState(vin = "VINOLD000"))

        val gateway = FakeToyotaGateway(
            fleet = listOf(vehicleRecord(VIN)),
            tripsProvider = { _, _, _ -> listOf(tripRecord("t-new", DAY_1.minusSeconds(60))) },
        )

        val ok = assertOk(ctx(gateway, stores).engine.fullSync())

        assertEquals(VIN, ok.state.vin)
        assertEquals(1, stores.trips.all("VINOLD000").size) // archive of the old car survives
        assertEquals(1, stores.trips.all(VIN).size)
    }

    // ---------------- costs (docs/SPEC.md §4, §5) ----------------

    @Test
    fun `recompute_costs_after_confirmed_price`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.refuels.addIfAbsent(confirmedRefuel(ts = DAY_1.minusSeconds(3_600)), VIN)
        stores.state.save(SyncState(vin = VIN))
        val gateway = FakeToyotaGateway()

        val ok = assertOk(ctx(gateway, stores).engine.recomputeCosts())

        assertEquals(1, ok.tripsCosted)
        val cost = requireNotNull(stores.costs.forTrip("t1"))
        assertEquals(CostSource.REFUEL, cost.source)
        assertEquals(0, BigDecimal("3.71").compareTo(requireNotNull(cost.costPln)))
        assertEquals(0, gateway.vehiclesCalls + gateway.telemetryCalls + gateway.tripsCalls)
    }

    @Test
    fun `cost_source_none_without_refuels`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.state.save(SyncState(vin = VIN))

        assertOk(ctx(FakeToyotaGateway(), stores).engine.recomputeCosts())

        val cost = requireNotNull(stores.costs.forTrip("t1"))
        assertEquals(CostSource.NONE, cost.source)
        assertNull(cost.costPln)
    }

    @Test
    fun `default_price_used_when_no_refuel`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.state.save(SyncState(vin = VIN))
        val c = ctx(FakeToyotaGateway(), stores, config = SyncConfig(defaultPrice = BigDecimal("5.99")))

        assertOk(c.engine.recomputeCosts())

        val cost = requireNotNull(stores.costs.forTrip("t1"))
        assertEquals(CostSource.DEFAULT, cost.source)
        assertEquals(0, BigDecimal("3.59").compareTo(requireNotNull(cost.costPln)))
    }

    @Test
    fun `full_sync_stores_costs_for_archived_trips`() = runTest {
        val stores = InMemoryStores()
        stores.refuels.addIfAbsent(confirmedRefuel(ts = DAY_1.minusSeconds(7_200)), VIN)
        val gateway = FakeToyotaGateway(
            fleet = listOf(vehicleRecord()),
            telemetry = reading(DAY_1, 40),
            tripsProvider = { _, _, _ -> listOf(tripRecord("t1", DAY_1.minusSeconds(3_600))) },
        )

        val ok = assertOk(ctx(gateway, stores).engine.fullSync())

        assertEquals(1, ok.tripsCosted)
        assertEquals(CostSource.REFUEL, requireNotNull(stores.costs.forTrip("t1")).source)
    }

    @Test
    fun `recompute_costs_without_vehicle_fails`() = runTest {
        val failed = assertFailed(ctx().engine.recomputeCosts(), SyncErrorKind.NO_VEHICLE)

        assertEquals(0, ctx().gateway.tripsCalls)
        assertTrue(!failed.error.retryable)
    }

    // ---------------- default price from a provider (docs/SPEC.md §12.1) ----------------

    @Test
    fun `default_price_provider_is_read_on_every_recount`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.state.save(SyncState(vin = VIN))
        var price: BigDecimal? = BigDecimal("6.20")
        val engine = ctx(FakeToyotaGateway(), stores, defaultPriceProvider = { price }).engine

        assertIs<CostRecompute.Done>(engine.recomputeCostsNow())

        val set = requireNotNull(stores.costs.forTrip("t1"))
        assertEquals(CostSource.DEFAULT, set.source)
        assertEquals(0, BigDecimal("3.72").compareTo(requireNotNull(set.costPln)))

        price = null
        assertIs<CostRecompute.Done>(engine.recomputeCostsNow())

        val removed = requireNotNull(stores.costs.forTrip("t1"))
        assertEquals(CostSource.NONE, removed.source, "usunieta cena wraca do 'brak ceny'")
        assertNull(removed.costPln)
    }

    @Test
    fun `default_price_provider_is_read_once_per_recount`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1), tripRecord("t2", DAY_2), tripRecord("t3", DAY_2)))
        stores.state.save(SyncState(vin = VIN))
        var reads = 0
        val engine = ctx(FakeToyotaGateway(), stores, defaultPriceProvider = { reads++; BigDecimal("6.20") }).engine

        val done = assertIs<CostRecompute.Done>(engine.recomputeCostsNow())

        assertEquals(3, done.tripsCosted)
        assertEquals(1, reads, "cena jest czytana raz na przeliczenie, nie raz na trase")
    }

    @Test
    fun `confirmed_refuel_beats_the_provider_price`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.refuels.addIfAbsent(confirmedRefuel(ts = DAY_1.minusSeconds(3_600)), VIN)
        stores.state.save(SyncState(vin = VIN))

        val engine = ctx(FakeToyotaGateway(), stores, defaultPriceProvider = { BigDecimal("10.00") }).engine
        assertIs<CostRecompute.Done>(engine.recomputeCostsNow())

        val cost = requireNotNull(stores.costs.forTrip("t1"))
        assertEquals(CostSource.REFUEL, cost.source)
        assertEquals(0, BigDecimal("3.71").compareTo(requireNotNull(cost.costPln)))
    }

    @Test
    fun `full_sync_uses_the_provider_price`() = runTest {
        val stores = InMemoryStores()
        val gateway = FakeToyotaGateway(
            fleet = listOf(vehicleRecord()),
            telemetry = reading(DAY_1, 40),
            tripsProvider = { _, _, _ -> listOf(tripRecord("t1", DAY_1.minusSeconds(3_600))) },
        )

        val engine = ctx(gateway, stores, defaultPriceProvider = { BigDecimal("6.20") }).engine
        assertOk(engine.fullSync())

        val cost = requireNotNull(stores.costs.forTrip("t1"))
        assertEquals(CostSource.DEFAULT, cost.source, "synchronizacja w tle tez zna aktualna cene")
        assertEquals(0, BigDecimal("3.72").compareTo(requireNotNull(cost.costPln)))
    }

    @Test
    fun `recompute_costs_now_waits_for_a_running_synchronization_instead_of_being_skipped`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord())).apply {
            beforeVehicles = {
                entered.complete(Unit)
                release.await()
            }
        }
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.state.save(SyncState(vin = VIN))
        val engine = ctx(gateway, stores, defaultPriceProvider = { BigDecimal("6.20") }).engine

        val sync = async { engine.fullSync() }
        entered.await()
        val recount = async { engine.recomputeCostsNow() }

        // The recount is queued behind the synchronization, so nothing is written yet.
        assertNull(stores.costs.forTrip("t1"))

        release.complete(Unit)
        assertOk(sync.await())
        assertEquals(1, assertIs<CostRecompute.Done>(recount.await()).tripsCosted)
        assertEquals(CostSource.DEFAULT, requireNotNull(stores.costs.forTrip("t1")).source)
    }

    @Test
    fun `recompute_costs_now_without_vehicle_reports_no_vehicle`() = runTest {
        val c = ctx(defaultPriceProvider = { BigDecimal("6.20") })

        assertIs<CostRecompute.NoVehicle>(c.engine.recomputeCostsNow())

        assertEquals(0, c.gateway.vehiclesCalls + c.gateway.telemetryCalls + c.gateway.tripsCalls)
    }

    // ---------------- trips list (docs/SPEC.md §10.1) ----------------

    @Test
    fun `trips_are_listed_newest_first`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(
            listOf(
                tripRecord("t-old", DAY_1.minusSeconds(86_400)),
                tripRecord("t-new", DAY_1),
                tripRecord("t-mid", DAY_1.minusSeconds(3_600)),
            ),
        )
        stores.state.save(SyncState(vin = VIN))

        val listed = ctx(FakeToyotaGateway(), stores).engine.trips()

        assertEquals(listOf("t-new", "t-mid", "t-old"), listed.map { it.record.id })
    }

    @Test
    fun `trips_carry_the_stored_cost`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.costs.saveAll(
            listOf(TripCost("t1", BigDecimal("3.68"), BigDecimal("6.140"), "refuel-1", CostSource.REFUEL)),
        )
        stores.state.save(SyncState(vin = VIN))

        val stored = ctx(FakeToyotaGateway(), stores).engine.trips().single()

        assertEquals("t1", stored.record.id)
        assertEquals(CostSource.REFUEL, requireNotNull(stored.cost).source)
        assertEquals(0, BigDecimal("3.68").compareTo(requireNotNull(stored.cost?.costPln)))
    }

    @Test
    fun `trip_without_a_cost_row_has_null_cost`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.state.save(SyncState(vin = VIN))

        val stored = ctx(FakeToyotaGateway(), stores).engine.trips().single()

        assertNull(stored.cost)
    }

    @Test
    fun `trips_of_another_vehicle_are_ignored`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(
            listOf(
                tripRecord("t-mine", DAY_1),
                tripRecord("t-other", DAY_2, vin = OTHER_VIN),
                tripRecord("t-old-car", DAY_1.minusSeconds(86_400), vin = "VINOLD000"),
            ),
        )
        stores.state.save(SyncState(vin = VIN))

        assertEquals(listOf("t-mine"), ctx(FakeToyotaGateway(), stores).engine.trips().map { it.record.id })
    }

    @Test
    fun `trips_are_empty_without_a_selected_vin`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))

        assertTrue(ctx(gateway, stores).engine.trips().isEmpty())
        assertEquals(0, gateway.vehiclesCalls + gateway.telemetryCalls + gateway.tripsCalls)
    }

    @Test
    fun `listing_trips_never_calls_the_gateway`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.state.save(SyncState(vin = VIN))
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))

        assertEquals(1, ctx(gateway, stores).engine.trips().size)

        assertEquals(0, gateway.vehiclesCalls + gateway.telemetryCalls + gateway.tripsCalls)
    }

    @Test
    fun `trips_read_all_costs_in_one_query`() = runTest {
        val stores = InMemoryStores()
        val many = (1..500).map { tripRecord("t$it", DAY_1.minusSeconds(it * 60L)) }
        stores.trips.upsertAll(many)
        stores.costs.saveAll(
            many.map { TripCost(it.id, BigDecimal("3.68"), BigDecimal("6.140"), "refuel-1", CostSource.REFUEL) },
        )
        stores.state.save(SyncState(vin = VIN))
        val counting = CountingCostRepository(stores.costs)
        val engine = SyncEngine(
            gateway = FakeToyotaGateway(),
            vehicles = stores.vehicles,
            trips = stores.trips,
            refuels = stores.refuels,
            readings = stores.readings,
            costs = counting,
            state = stores.state,
            clock = { DAY_1 },
        )

        assertEquals(500, engine.trips().size)

        assertEquals(1, counting.allCalls, "costs must be read with a single query")
        assertEquals(0, counting.forTripCalls, "no cost query per trip")
    }

    // ---------------- refuel price (docs/SPEC.md §11) ----------------

    @Test
    fun `pending_refuels_are_listed_newest_first`() = runTest {
        val stores = InMemoryStores()
        stores.state.save(SyncState(vin = VIN))
        stores.refuels.addIfAbsent(pendingRefuel("r-old", DAY_1), VIN)
        stores.refuels.addIfAbsent(pendingRefuel("r-new", DAY_2), VIN)
        stores.refuels.addIfAbsent(confirmedRefuel(ts = DAY_1), VIN)

        val ids = ctx(FakeToyotaGateway(), stores).engine.pendingRefuels().map { it.id }

        assertEquals(listOf("r-new", "r-old"), ids)
    }

    @Test
    fun `pending_refuels_of_another_vehicle_are_ignored`() = runTest {
        val stores = InMemoryStores()
        stores.state.save(SyncState(vin = VIN))
        stores.refuels.addIfAbsent(pendingRefuel("r-mine", DAY_1), VIN)
        stores.refuels.addIfAbsent(pendingRefuel("r-theirs", DAY_2), OTHER_VIN)

        val ids = ctx(FakeToyotaGateway(), stores).engine.pendingRefuels().map { it.id }

        assertEquals(listOf("r-mine"), ids)
    }

    @Test
    fun `pending_refuels_are_empty_without_a_selected_vin`() = runTest {
        val stores = InMemoryStores()
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1), VIN)

        assertTrue(ctx(FakeToyotaGateway(), stores).engine.pendingRefuels().isEmpty())
    }

    @Test
    fun `listing_pending_refuels_never_calls_the_gateway`() = runTest {
        val stores = InMemoryStores()
        stores.state.save(SyncState(vin = VIN))
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1), VIN)
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))

        ctx(gateway, stores).engine.pendingRefuels()

        assertEquals(0, gateway.vehiclesCalls + gateway.telemetryCalls + gateway.tripsCalls)
    }

    @Test
    fun `confirm_price_only_saves_confirmed_refuel_and_costs_the_trip`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1.minusSeconds(3_600)), VIN)
        stores.state.save(SyncState(vin = VIN))

        val change = assertIs<RefuelChange.Done>(
            ctx(FakeToyotaGateway(), stores).engine.confirmRefuel("r1", pricePerLiter = BigDecimal("6.19")),
        )

        assertEquals(1, change.tripsCosted)
        assertEquals(RefuelStatus.CONFIRMED, change.refuel.status)
        val stored = requireNotNull(stores.refuels.byId("r1"))
        assertEquals(0, BigDecimal("6.190").compareTo(requireNotNull(stored.pricePerLiter)))
        assertNull(stored.liters, "liters stay unknown when only the price was given (docs/SPEC.md §2)")
        val cost = requireNotNull(stores.costs.forTrip("t1"))
        assertEquals(CostSource.REFUEL, cost.source)
        assertEquals(0, BigDecimal("3.71").compareTo(requireNotNull(cost.costPln)))
    }

    @Test
    fun `confirm_liters_and_total_derive_the_price`() = runTest {
        val stores = InMemoryStores()
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1), VIN)
        stores.state.save(SyncState(vin = VIN))

        val change = assertIs<RefuelChange.Done>(
            ctx(FakeToyotaGateway(), stores).engine.confirmRefuel(
                "r1",
                liters = BigDecimal("25.800"),
                totalCost = BigDecimal("159.67"),
            ),
        )

        assertEquals(RefuelStatus.CONFIRMED, change.refuel.status)
        assertEquals(0, BigDecimal("6.189").compareTo(requireNotNull(change.refuel.pricePerLiter)))
        assertEquals(LitersSource.RECEIPT, change.refuel.litersSource, "liters came from the keyboard")
        assertEquals(0, change.tripsCosted, "no trips are archived yet")
    }

    @Test
    fun `confirm_accepted_estimate_saves_the_source_and_the_computed_total`() = runTest {
        val stores = InMemoryStores()
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1), VIN)
        stores.state.save(SyncState(vin = VIN))

        val change = assertIs<RefuelChange.Done>(
            ctx(FakeToyotaGateway(), stores).engine.confirmRefuel(
                "r1",
                pricePerLiter = BigDecimal("6.50"),
                liters = BigDecimal("14.190"),
                litersSource = LitersSource.ESTIMATE,
            ),
        )

        val stored = requireNotNull(stores.refuels.byId("r1"))
        assertEquals(RefuelStatus.CONFIRMED, stored.status)
        assertEquals(0, BigDecimal("14.190").compareTo(requireNotNull(stored.liters)))
        assertEquals(LitersSource.ESTIMATE, stored.litersSource, "the user accepted the sensor estimate (F2a)")
        assertEquals(0, BigDecimal("92.24").compareTo(requireNotNull(stored.totalCost)))
        assertEquals(0, change.tripsCosted, "no trips are archived yet")
    }

    @Test
    fun `confirm_estimate_source_without_liters_is_invalid_and_saves_nothing`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1), VIN)
        stores.state.save(SyncState(vin = VIN))

        // The domain rejects a source without liters; the engine only forwards it (SPEC §11.1).
        assertIs<RefuelChange.Invalid>(
            ctx(FakeToyotaGateway(), stores).engine.confirmRefuel(
                "r1",
                pricePerLiter = BigDecimal("6.50"),
                litersSource = LitersSource.ESTIMATE,
            ),
        )

        val stored = requireNotNull(stores.refuels.byId("r1"))
        assertEquals(RefuelStatus.PENDING, stored.status)
        assertNull(stored.pricePerLiter)
        assertNull(stored.liters)
        assertNull(stored.litersSource, "a rejected write may not record a source")
        assertNull(stores.costs.forTrip("t1"), "a rejected price must not recount costs")
    }

    @Test
    fun `confirm_with_bad_combination_is_invalid_and_saves_nothing`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1), VIN)
        stores.state.save(SyncState(vin = VIN))

        assertIs<RefuelChange.Invalid>(
            ctx(FakeToyotaGateway(), stores).engine.confirmRefuel("r1", liters = BigDecimal("25.800")),
        )

        assertEquals(RefuelStatus.PENDING, requireNotNull(stores.refuels.byId("r1")).status)
        assertNull(stores.costs.forTrip("t1"), "a rejected price must not recount costs")
    }

    @Test
    fun `confirm_non_positive_value_is_invalid_and_saves_nothing`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1), VIN)
        stores.state.save(SyncState(vin = VIN))

        assertIs<RefuelChange.Invalid>(
            ctx(FakeToyotaGateway(), stores).engine.confirmRefuel("r1", pricePerLiter = BigDecimal("-5")),
        )

        assertEquals(RefuelStatus.PENDING, requireNotNull(stores.refuels.byId("r1")).status)
        assertNull(stores.costs.forTrip("t1"))
    }

    @Test
    fun `confirm_unknown_id_is_not_found`() = runTest {
        val stores = InMemoryStores()
        stores.state.save(SyncState(vin = VIN))

        assertIs<RefuelChange.NotFound>(
            ctx(FakeToyotaGateway(), stores).engine.confirmRefuel("r-none", pricePerLiter = BigDecimal("6.19")),
        )
    }

    @Test
    fun `confirm_refuel_of_another_vehicle_is_not_found`() = runTest {
        val stores = InMemoryStores()
        stores.refuels.addIfAbsent(pendingRefuel("r-theirs", DAY_1), OTHER_VIN)
        stores.state.save(SyncState(vin = VIN))
        val engine = ctx(FakeToyotaGateway(), stores).engine

        assertIs<RefuelChange.NotFound>(engine.confirmRefuel("r-theirs", pricePerLiter = BigDecimal("6.19")))

        assertEquals(RefuelStatus.PENDING, requireNotNull(stores.refuels.byId("r-theirs")).status)
    }

    @Test
    fun `confirm_refuel_without_a_selected_vehicle_reports_no_vehicle`() = runTest {
        val stores = InMemoryStores()
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1), VIN)

        assertIs<RefuelChange.NoVehicle>(
            ctx(FakeToyotaGateway(), stores).engine.confirmRefuel("r1", pricePerLiter = BigDecimal("6.19")),
        )

        assertEquals(RefuelStatus.PENDING, requireNotNull(stores.refuels.byId("r1")).status)
    }

    @Test
    fun `confirming_twice_reports_not_pending_and_keeps_the_first_price`() = runTest {
        val stores = InMemoryStores()
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1), VIN)
        stores.state.save(SyncState(vin = VIN))
        val engine = ctx(FakeToyotaGateway(), stores).engine
        assertIs<RefuelChange.Done>(engine.confirmRefuel("r1", pricePerLiter = BigDecimal("6.19")))

        assertIs<RefuelChange.NotPending>(engine.confirmRefuel("r1", pricePerLiter = BigDecimal("6.25")))

        val stored = requireNotNull(stores.refuels.byId("r1"))
        assertEquals(0, BigDecimal("6.190").compareTo(requireNotNull(stored.pricePerLiter)))
    }

    @Test
    fun `dismiss_saves_dismissed_and_recounts_costs`() = runTest {
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1.minusSeconds(3_600)), VIN)
        stores.state.save(SyncState(vin = VIN))
        val engine = ctx(FakeToyotaGateway(), stores).engine

        val dismissed = assertIs<RefuelChange.Done>(engine.dismissRefuel("r1"))

        assertEquals(RefuelStatus.DISMISSED, dismissed.refuel.status)
        assertEquals(1, dismissed.tripsCosted)
        // A dismissed refuel is ignored by docs/SPEC.md §4, so the trip stays without a price.
        assertEquals(CostSource.NONE, requireNotNull(stores.costs.forTrip("t1")).source)
        assertIs<RefuelChange.NotPending>(engine.dismissRefuel("r1"))
    }

    @Test
    fun `confirm_waits_for_a_running_synchronization_instead_of_being_skipped`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord())).apply {
            beforeVehicles = {
                entered.complete(Unit)
                release.await()
            }
        }
        val stores = InMemoryStores()
        stores.trips.upsertAll(listOf(tripRecord("t1", DAY_1)))
        stores.refuels.addIfAbsent(pendingRefuel("r1", DAY_1.minusSeconds(3_600)), VIN)
        stores.state.save(SyncState(vin = VIN))
        val engine = ctx(gateway, stores).engine

        val sync = async { engine.fullSync() }
        entered.await()
        val confirm = async { engine.confirmRefuel("r1", pricePerLiter = BigDecimal("6.19")) }

        // Queued behind the running synchronization: still pending, and not skipped.
        assertEquals(RefuelStatus.PENDING, requireNotNull(stores.refuels.byId("r1")).status)

        release.complete(Unit)
        assertIs<SyncOutcome.Ok>(sync.await())
        val change = assertIs<RefuelChange.Done>(confirm.await())

        assertEquals(RefuelStatus.CONFIRMED, change.refuel.status)
        assertEquals(CostSource.REFUEL, requireNotNull(stores.costs.forTrip("t1")).source)
        assertEquals(1, gateway.vehiclesCalls, "the confirmation did not start another synchronization")
    }

    // ---------------- concurrency, status ----------------

    @Test
    fun `sync_is_single_flight`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord())).apply {
            beforeVehicles = {
                entered.complete(Unit)
                release.await()
            }
        }
        val c = ctx(gateway)

        val first = async { c.engine.fullSync() }
        entered.await()

        assertIs<SyncOutcome.Skipped>(c.engine.fullSync())

        release.complete(Unit)
        first.join()

        assertIs<SyncOutcome.Ok>(first.await())
        assertEquals(1, gateway.vehiclesCalls)
    }

    @Test
    fun `status_counts_and_freshness`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))
        gateway.enqueueTelemetry(reading(DAY_1, 30))
        gateway.enqueueTelemetry(reading(DAY_2, 45))
        val c = ctx(gateway)

        val before = c.engine.status()
        assertTrue(!before.isFresh)
        assertEquals(0, before.tripsStored)

        assertOk(c.engine.fullSync())
        c.clock.at(DAY_2)
        assertOk(c.engine.fullSync())

        val after = c.engine.status()
        assertTrue(after.isFresh)
        assertEquals(2, after.readingsStored)
        assertEquals(1, after.pendingRefuels)
        assertEquals(VIN, after.state.vin)
    }

    @Test
    fun `stale_flag_clears_after_next_success`() = runTest {
        val gateway = FakeToyotaGateway(fleet = listOf(vehicleRecord()))
        val c = ctx(gateway)
        assertOk(c.engine.fullSync())

        gateway.tripsError = ToyotaDataException("unexpected json")
        c.clock.at(DAY_2)
        assertFailed(c.engine.fullSync(), SyncErrorKind.DATA)
        val stale = c.engine.status()
        assertTrue(!stale.isFresh, "state=${stale.state}")

        gateway.tripsError = null
        c.clock.at(DAY_2.plusSeconds(3_600))
        assertOk(c.engine.fullSync())

        assertTrue(c.engine.status().isFresh)
        assertNull(c.stores.state.get().lastErrorKind)
    }
}

/**
 * Counts the cost reads of [SyncEngine.trips] — docs/SPEC.md §10.1 forbids one
 * query per trip (the archive holds hundreds of trips).
 */
private class CountingCostRepository(private val delegate: InMemoryCostRepository) : CostRepository {
    var allCalls = 0
    var forTripCalls = 0

    override suspend fun saveAll(costs: List<TripCost>): Int = delegate.saveAll(costs)

    override suspend fun forTrip(tripId: String): TripCost? {
        forTripCalls++
        return delegate.forTrip(tripId)
    }

    override suspend fun all(): List<TripCost> {
        allCalls++
        return delegate.all()
    }

    override suspend fun count(): Int = delegate.count()

    override suspend fun deleteAll() = delegate.deleteAll()
}
