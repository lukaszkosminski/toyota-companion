package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
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

private val POINTS = listOf(TripRoutePoint(54.372, 18.638, true), TripRoutePoint(54.373, 18.639, false))

private fun ride(id: String) = StoredTrip(
    TripRecord(Trip(id, Instant.parse("2026-10-30T08:00:00Z"), Instant.parse("2026-10-30T09:00:00Z"), 10.0, BigDecimal("0.5"), null), "VINAAA111"),
    null,
)

private class FakeRoutes : TripRouteGateway {
    var calls = 0
    var askedVin: String? = null
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var answer: List<TripRoutePoint> = POINTS

    override suspend fun route(vin: String, tripId: String): List<TripRoutePoint> {
        calls++
        askedVin = vin
        gate?.await()
        failure?.let { throw it }
        return answer
    }
}

/** docs/SPEC.md §27: the route of a trip comes from the phone, or from one Toyota GET that is then kept. */
class TripRouteControllerTest {
    private fun controller(gateway: FakeRoutes, repo: InMemoryTripRouteRepository, io: kotlinx.coroutines.CoroutineDispatcher) =
        TripRouteController({ listOf(ride("t1")) }, repo, gateway, CoroutineScope(SupervisorJob() + io), io)

    @Test
    fun `the first open asks Toyota once with the trips vin and keeps the route`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeRoutes()
        val repo = InMemoryTripRouteRepository()
        val c = controller(gateway, repo, io)
        c.open("t1")
        assertEquals(RouteState.Loading, c.state.value)
        advanceUntilIdle()
        assertEquals(RouteState.Ready(POINTS), c.state.value)
        assertEquals("VINAAA111", gateway.askedVin)
        assertEquals(POINTS, repo.get("t1"))
        c.open("t1")
        advanceUntilIdle()
        assertEquals(1, gateway.calls, "the second open reads the phone")
        assertEquals(RouteState.Ready(POINTS), c.state.value)
    }

    @Test
    fun `a parking place asks for nothing`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeRoutes()
        val c = controller(gateway, InMemoryTripRouteRepository(), io)
        c.open(null)
        advanceUntilIdle()
        assertEquals(RouteState.None, c.state.value)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun `an empty route and a 404 are Missing and are remembered`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeRoutes().apply { answer = emptyList() }
        val repo = InMemoryTripRouteRepository()
        val c = controller(gateway, repo, io)
        c.open("t1")
        advanceUntilIdle()
        assertEquals(RouteState.Missing, c.state.value)
        c.open("t1")
        advanceUntilIdle()
        assertEquals(1, gateway.calls)

        val gone = FakeRoutes().apply { failure = ToyotaApiException(404, "not found") }
        val repo2 = InMemoryTripRouteRepository()
        val c2 = controller(gone, repo2, io)
        c2.open("t1")
        advanceUntilIdle()
        assertEquals(RouteState.Missing, c2.state.value)
        assertEquals(emptyList(), repo2.get("t1"))
    }

    @Test
    fun `failures are fixed reasons, are not saved and are asked again on the next open`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val repo = InMemoryTripRouteRepository()
        val gateway = FakeRoutes().apply { failure = ToyotaNetworkException("secret host") }
        val c = controller(gateway, repo, io)
        c.open("t1")
        advanceUntilIdle()
        assertEquals(RouteState.Failed(RouteFailure.NETWORK), c.state.value)
        gateway.failure = NoCredentialsException()
        c.open("t1")
        advanceUntilIdle()
        assertEquals(RouteState.Failed(RouteFailure.LOGIN), c.state.value)
        gateway.failure = ToyotaApiException(500, "boom")
        c.open("t1")
        advanceUntilIdle()
        assertEquals(RouteState.Failed(RouteFailure.API), c.state.value)
        assertEquals(0, repo.count())
        gateway.failure = null
        c.open("t1")
        advanceUntilIdle()
        assertEquals(RouteState.Ready(POINTS), c.state.value)
        for (reason in RouteFailure.entries) {
            val text = Texts.mapRouteNote(RouteState.Failed(reason))
            assertTrue(text.isNotBlank() && !text.contains("secret"))
        }
    }

    @Test
    fun `an unknown trip is an API failure, not a crash`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val c = controller(FakeRoutes(), InMemoryTripRouteRepository(), io)
        c.open("nope")
        advanceUntilIdle()
        assertEquals(RouteState.Failed(RouteFailure.API), c.state.value)
    }

    @Test
    fun `closing the map cancels a running request and a late answer is dropped`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeRoutes().apply { gate = CompletableDeferred() }
        val repo = InMemoryTripRouteRepository()
        val c = controller(gateway, repo, io)
        c.open("t1")
        advanceUntilIdle()
        c.close()
        gateway.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(RouteState.None, c.state.value)
        assertEquals(0, repo.count())
    }

    @Test
    fun `the page gets only numbers`() {
        assertEquals("[[54.37200,18.63800,1],[54.37300,18.63900,0],[1.00000,2.00000,-1]]", routeJson(POINTS + TripRoutePoint(1.0, 2.0, null)))
        assertEquals("[]", routeJson(emptyList()))
    }

    @Test
    fun `every route state has a Polish note`() {
        val states = listOf(RouteState.None, RouteState.Loading, RouteState.Ready(POINTS), RouteState.Missing)
        assertEquals(states.size, states.map { Texts.mapRouteNote(it) }.toSet().size)
    }
}
