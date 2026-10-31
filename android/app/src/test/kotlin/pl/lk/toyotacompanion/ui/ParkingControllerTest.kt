package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val START: Instant = Instant.parse("2026-10-29T13:05:00Z")
private val NEXT: Instant = Instant.parse("2026-10-30T06:40:00Z")

private const val LAT = 54.372
private const val LON = 18.638

/** One archived trip parked at `LAT, LON`; the diary is built from these two rows. */
private fun parkedTrip(id: String, start: Instant): StoredTrip = StoredTrip(
    record = TripRecord(
        trip = Trip(
            id = id,
            start = start,
            end = start.plusSeconds(1_800),
            distanceKm = 9.0,
            fuelL = BigDecimal("0.700"),
            evDistanceKm = null,
        ),
        vin = "VINAAA111",
        startLat = LAT,
        startLon = LON,
        endLat = LAT,
        endLon = LON,
    ),
    cost = null,
)

/** The archive behind the controller: counts the reads and answers what the test decided. */
private class FakeTrips {
    var trips = listOf(parkedTrip("a", START), parkedTrip("b", NEXT))
    var error: Exception? = null

    /** Holds the read open, like a screen opened while the first read still runs. */
    var gate: CompletableDeferred<Unit>? = null
    var calls = 0

    suspend fun read(): List<StoredTrip> {
        calls++
        gate?.await()
        error?.let { throw it }
        return trips
    }
}

/** docs/SPEC.md §18: the state of "Postoje" and when it reads the archive. */
class ParkingControllerTest {

    private fun controller(reads: FakeTrips, io: CoroutineDispatcher) = ParkingController(
        read = { reads.read() },
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
    )

    @Test
    fun `the navigation callback reads and a restored screen does not read again`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val reads = FakeTrips()
        val parking = controller(reads, io)

        parking.ensureLoaded()
        advanceUntilIdle()
        parking.ensureLoaded()
        advanceUntilIdle()

        assertEquals(1, reads.calls, "one read per navigation, the second caller is a recomposition")
        val log = requireNotNull(parking.state.value.log)
        assertEquals(1, log.stops.size, "the gap between the two trips is one stop")
        assertEquals(1, log.places.size, "and it belongs to one place")
        assertFalse(parking.state.value.loading, "the diary is on screen")
        assertFalse(parking.state.value.loadFailed)
    }

    @Test
    fun `a late result of the old session is dropped after logout`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val reads = FakeTrips()
        reads.gate = CompletableDeferred()
        val parking = controller(reads, io)

        parking.load()
        advanceUntilIdle()
        assertTrue(parking.state.value.loading, "the read is still open")

        parking.reset()
        reads.gate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(parking.state.value.loading, "the finished read belongs to the old session")
        assertNull(parking.state.value.log, "a logged out user sees no diary")
    }

    @Test
    fun `a read after logout reads the archive again`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val reads = FakeTrips()
        val parking = controller(reads, io)

        parking.load()
        advanceUntilIdle()
        parking.reset()
        parking.load()
        advanceUntilIdle()

        assertEquals(2, reads.calls, "reset cleared the guard, not the controller")
        val log = requireNotNull(parking.state.value.log)
        assertEquals(1, log.stops.size, "the new session gets its own diary")
    }

    @Test
    fun `a failing read is shown without its text`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val reads = FakeTrips()
        reads.error = IllegalStateException("no such column trips.end_lat in /data/user/0.db")
        val parking = controller(reads, io)

        parking.load()
        advanceUntilIdle()

        assertTrue(parking.state.value.loadFailed, "the screen shows its own text")
        assertNull(parking.state.value.log, "nothing of the failed read is kept")
    }

    @Test
    fun `a good read clears an earlier failure`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val reads = FakeTrips()
        reads.error = IllegalStateException("database busy")
        val parking = controller(reads, io)

        parking.load()
        advanceUntilIdle()
        reads.error = null
        parking.load()
        advanceUntilIdle()

        assertFalse(parking.state.value.loadFailed, "a retry replaces the failure")
        assertEquals(1, requireNotNull(parking.state.value.log).stops.size)
    }

    @Test
    fun `an empty archive is an empty diary, not a failure`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val reads = FakeTrips()
        reads.trips = listOf(parkedTrip("only", START))
        val parking = controller(reads, io)

        parking.load()
        advanceUntilIdle()

        val log = requireNotNull(parking.state.value.log)
        assertTrue(log.stops.isEmpty(), "one trip leaves no gap between trips")
        assertTrue(log.places.isEmpty())
        assertFalse(parking.state.value.loadFailed, "an empty diary is a normal state")
    }
}
