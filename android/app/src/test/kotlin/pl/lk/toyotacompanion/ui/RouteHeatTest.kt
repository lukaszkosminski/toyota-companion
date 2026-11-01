package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
import pl.lk.toyotacompanion.core.routes.TripRoutePoint

private val LINE = listOf(TripRoutePoint(54.37201, 18.63801, true), TripRoutePoint(54.38001, 18.64001, false), TripRoutePoint(54.39001, 18.65001, null))

private fun drive(id: String, day: Int, km: Double, fuel: String) = StoredTrip(
    TripRecord(
        Trip(id, Instant.parse("2026-10-${day.toString().padStart(2, '0')}T08:00:00Z"), Instant.parse("2026-10-${day.toString().padStart(2, '0')}T09:00:00Z"), km, BigDecimal(fuel), null),
        "VINAAA111",
        startLat = 54.372,
        startLon = 18.638,
        endLat = 54.39,
        endLon = 18.65,
    ),
    null,
)

/** docs/SPEC.md §34: the consumption heat map of one repeated route, from the routes stored on the phone. */
class RouteHeatTest {
    private val trips = listOf(drive("a", 1, 10.0, "0.4"), drive("b", 2, 10.0, "0.6"), drive("c", 3, 10.0, "0.5"), drive("d", 4, 10.0, "0.8"))

    private fun controller(repo: InMemoryTripRouteRepository, io: kotlinx.coroutines.CoroutineDispatcher, list: List<StoredTrip> = trips) =
        RouteHeatController({ list }, repo, CoroutineScope(SupervisorJob() + io), io)

    @Test
    fun `the share runs from the best drive to the worst`() {
        assertEquals(0f, heatShare(BigDecimal("4.0"), BigDecimal("4.0"), BigDecimal("8.0")))
        assertEquals(1f, heatShare(BigDecimal("8.0"), BigDecimal("4.0"), BigDecimal("8.0")))
        assertEquals(0.5f, heatShare(BigDecimal("6.0"), BigDecimal("4.0"), BigDecimal("8.0")))
        assertEquals(0.5f, heatShare(BigDecimal("5.0"), BigDecimal("5.0"), BigDecimal("5.0")), "equal drives are all in the middle")
    }

    @Test
    fun `only the drives with a stored route are drawn, coloured between the drawn best and worst`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val repo = InMemoryTripRouteRepository().apply {
            save("a", LINE)
            save("b", LINE)
            save("c", emptyList())
            // "d" has no stored route at all
        }
        val c = controller(repo, io)
        c.open(listOf("a", "b", "c", "d"))
        assertEquals(HeatState.Loading, c.state.value)
        advanceUntilIdle()
        val ready = c.state.value as HeatState.Ready
        assertEquals(2, ready.lines.size)
        assertEquals(4, ready.total)
        assertEquals(BigDecimal("4.00"), ready.minLPer100)
        assertEquals(BigDecimal("6.00"), ready.maxLPer100)
        assertEquals(listOf(0f, 1f), ready.lines.map { it.share })
        assertEquals(listOf(Texts.MAP_START, Texts.MAP_END), ready.points.map { it.label })
        assertEquals(54.372, ready.points.first().lat)
    }

    @Test
    fun `without a single stored route the map is empty and says how many drives there are`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val c = controller(InMemoryTripRouteRepository(), io)
        c.open(listOf("a", "b", "c"))
        advanceUntilIdle()
        assertEquals(HeatState.Empty(3), c.state.value)
        assertTrue(Texts.heatNote(HeatState.Empty(3)).contains("3 przejazdów"))
    }

    @Test
    fun `a broken read is a failure, close forgets and a new open replaces the old one`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val failing = RouteHeatController({ throw IllegalStateException("db") }, InMemoryTripRouteRepository(), CoroutineScope(SupervisorJob() + io), io)
        failing.open(listOf("a"))
        advanceUntilIdle()
        assertEquals(HeatState.Failed, failing.state.value)
        failing.close()
        assertEquals(HeatState.None, failing.state.value)

        val repo = InMemoryTripRouteRepository().apply { save("a", LINE); save("b", LINE) }
        val c = controller(repo, io)
        c.open(listOf("a"))
        c.open(listOf("b"))
        advanceUntilIdle()
        assertEquals(1, (c.state.value as HeatState.Ready).lines.size)
    }

    @Test
    fun `the page gets numbers and the consumption text only`() {
        val json = heatJson(listOf(HeatLine(LINE, BigDecimal("4.30"), 0.25f), HeatLine(LINE.take(2), BigDecimal("6.00"), 1f)))
        assertTrue(json.startsWith("""[{"p":[[54.37201,18.63801],[54.38001,18.64001],[54.39001,18.65001]],"f":0.250,"t":"4,3 l/100 km"}"""))
        assertTrue(json.contains(""""f":1.000,"t":"6,0 l/100 km"}]"""))
        assertEquals("[]", heatJson(emptyList()))
    }

    @Test
    fun `every state has a note and the ready one names the range`() {
        val ready = HeatState.Ready(emptyList(), emptyList(), 5, BigDecimal("4.0"), BigDecimal("6.5"))
        assertTrue(Texts.heatNote(ready).contains("4,0 l/100 km") && Texts.heatNote(ready).contains("6,5"))
        for (state in listOf(HeatState.Loading, HeatState.Failed, HeatState.Empty(2), ready)) assertTrue(Texts.heatNote(state).isNotBlank())
    }
}
