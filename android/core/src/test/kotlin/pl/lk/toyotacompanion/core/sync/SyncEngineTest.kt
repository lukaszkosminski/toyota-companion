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
