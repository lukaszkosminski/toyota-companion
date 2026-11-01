package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.data.InMemoryTripRouteRepository
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.routes.TripRouteGateway
import pl.lk.toyotacompanion.core.routes.TripRoutePoint
import pl.lk.toyotacompanion.core.sync.NoCredentialsException
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException
import pl.lk.toyotacompanion.core.toyota.ToyotaNetworkException

private val BACKFILL_ROUTE = listOf(TripRoutePoint(54.372, 18.638, true), TripRoutePoint(54.373, 18.639, false))

private fun backfillRide(id: String, day: Int, withPosition: Boolean = true) = StoredTrip(
    TripRecord(
        Trip(id, Instant.parse("2026-10-${day.toString().padStart(2, '0')}T08:00:00Z"), Instant.parse("2026-10-${day.toString().padStart(2, '0')}T09:00:00Z"), 10.0, BigDecimal("0.5"), null),
        "VINAAA111",
        startLat = if (withPosition) 54.37 else null,
        startLon = if (withPosition) 18.63 else null,
    ),
    null,
)

private class FakeBackfillRoutes : TripRouteGateway {
    val asked = ArrayList<String>()
    var failAt: Pair<Int, Exception>? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun route(vin: String, tripId: String): List<TripRoutePoint> {
        asked += tripId
        gate?.await()
        failAt?.let { (n, e) -> if (asked.size == n) throw e }
        return BACKFILL_ROUTE
    }
}

/** docs/SPEC.md §33: the routes of older trips are saved on a tap, oldest first, before Toyota removes them. */
class RouteBackfillControllerTest {
    private fun controller(trips: List<StoredTrip>, repo: InMemoryTripRouteRepository, gateway: FakeBackfillRoutes, io: kotlinx.coroutines.CoroutineDispatcher) =
        RouteBackfillController({ trips }, repo, gateway, CoroutineScope(SupervisorJob() + io), io, pause = {})

    @Test
    fun `a run saves the missing routes, oldest first, and skips what is stored and trips without a position`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val repo = InMemoryTripRouteRepository().apply { save("t2", BACKFILL_ROUTE) }
        val gateway = FakeBackfillRoutes()
        val trips = listOf(backfillRide("t3", 3), backfillRide("t1", 1), backfillRide("t2", 2), backfillRide("none", 4, withPosition = false))
        val c = controller(trips, repo, gateway, io)
        c.refresh()
        advanceUntilIdle()
        assertEquals(3, c.state.value.total)
        assertEquals(1, c.state.value.stored)
        c.start()
        advanceUntilIdle()
        assertEquals(listOf("t1", "t3"), gateway.asked)
        assertEquals(BackfillResult.DONE, c.state.value.result)
        assertEquals(3, c.state.value.stored)
        assertEquals(setOf("t1", "t2", "t3"), repo.storedIds())
        assertFalse(c.state.value.running)
    }

    @Test
    fun `a run does at most one hundred and the next tap does the rest`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeBackfillRoutes()
        val trips = (1..130).map { backfillRide("t$it", 1 + it % 28) }
        val repo = InMemoryTripRouteRepository()
        val c = controller(trips, repo, gateway, io)
        c.start()
        advanceUntilIdle()
        assertEquals(100, gateway.asked.size)
        assertEquals(BackfillResult.PARTIAL, c.state.value.result)
        c.start()
        advanceUntilIdle()
        assertEquals(130, repo.count())
        assertEquals(BackfillResult.DONE, c.state.value.result)
    }

    @Test
    fun `a trip Toyota no longer has is stored as no route, any other failure stops the run`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeBackfillRoutes().apply { failAt = 1 to ToyotaApiException(404, "gone") }
        val repo = InMemoryTripRouteRepository()
        val c = controller(listOf(backfillRide("t1", 1), backfillRide("t2", 2)), repo, gateway, io)
        c.start()
        advanceUntilIdle()
        assertEquals(emptyList(), repo.get("t1"))
        assertEquals(BACKFILL_ROUTE, repo.get("t2"))
        assertEquals(BackfillResult.DONE, c.state.value.result)

        val failing = FakeBackfillRoutes().apply { failAt = 2 to ToyotaNetworkException("host") }
        val repo2 = InMemoryTripRouteRepository()
        val c2 = controller(listOf(backfillRide("a", 1), backfillRide("b", 2), backfillRide("c", 3)), repo2, failing, io)
        c2.start()
        advanceUntilIdle()
        assertEquals(BackfillResult.NETWORK, c2.state.value.result)
        assertEquals(setOf("a"), repo2.storedIds())
        assertEquals(1, c2.state.value.fetchedNow)
    }

    @Test
    fun `login problems and server errors are fixed results and nothing is saved for the failed trip`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        for ((error, expected) in listOf(NoCredentialsException() to BackfillResult.LOGIN, ToyotaApiException(500, "boom") to BackfillResult.API)) {
            val repo = InMemoryTripRouteRepository()
            val c = controller(listOf(backfillRide("a", 1)), repo, FakeBackfillRoutes().apply { failAt = 1 to error }, io)
            c.start()
            advanceUntilIdle()
            assertEquals(expected, c.state.value.result)
            assertEquals(0, repo.count())
        }
    }

    @Test
    fun `nothing missing says so, a second tap while running does nothing and stop keeps what was saved`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val done = controller(listOf(backfillRide("a", 1)), InMemoryTripRouteRepository().apply { save("a", BACKFILL_ROUTE) }, FakeBackfillRoutes(), io)
        done.start()
        advanceUntilIdle()
        assertEquals(BackfillResult.NOTHING, done.state.value.result)

        val gateway = FakeBackfillRoutes().apply { gate = CompletableDeferred() }
        val repo = InMemoryTripRouteRepository()
        val c = controller(listOf(backfillRide("a", 1), backfillRide("b", 2)), repo, gateway, io)
        c.start()
        advanceUntilIdle()
        assertTrue(c.state.value.running)
        c.start()
        advanceUntilIdle()
        assertEquals(1, gateway.asked.size)
        c.stop()
        gateway.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(BackfillResult.STOPPED, c.state.value.result)
        assertFalse(c.state.value.running)
    }

    @Test
    fun `reset forgets everything and every result has a Polish text`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val c = controller(listOf(backfillRide("a", 1)), InMemoryTripRouteRepository(), FakeBackfillRoutes(), io)
        c.start()
        advanceUntilIdle()
        c.reset()
        assertEquals(BackfillState(), c.state.value)
        for (r in BackfillResult.entries) assertTrue(Texts.backfillResult(r, 3).isNotBlank())
    }
}
