package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun drive(id: String, start: String) = StoredTrip(
    TripRecord(Trip(id, Instant.parse(start), Instant.parse(start).plusSeconds(1_200), 12.0, BigDecimal("0.50"), 5.0), "VINAAA111"),
    null,
)

/** docs/SPEC.md §19: the controller of "Styl jazdy" (C22 D12), including the newest-read-wins rule (C22 D5). */
class DrivingStyleControllerTest {

    private fun TestScope.controller(read: suspend () -> List<StoredTrip>): DrivingStyleController {
        val io = StandardTestDispatcher(testScheduler)
        return DrivingStyleController(read, { WARSAW }, CoroutineScope(SupervisorJob() + io), io)
    }

    @Test
    fun `it starts loading and shows the view of the trips`() = runTest {
        val c = controller { listOf(drive("a", "2026-10-01T08:00:00Z"), drive("b", "2026-10-02T08:00:00Z")) }
        assertTrue(c.state.value.loading)

        c.load()
        advanceUntilIdle()

        val state = c.state.value
        assertFalse(state.loading)
        assertFalse(state.loadFailed)
        assertEquals(2, state.view?.tripCount)
    }

    @Test
    fun `ensureLoaded reads once and load reads again`() = runTest {
        var reads = 0
        val c = controller { reads++; emptyList() }

        c.ensureLoaded()
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, reads)

        c.load()
        advanceUntilIdle()
        assertEquals(2, reads)
    }

    @Test
    fun `a failing read is shown as a failure without a view`() = runTest {
        val c = controller { throw IllegalStateException("disk full at /data/secret") }

        c.load()
        advanceUntilIdle()

        assertTrue(c.state.value.loadFailed)
        assertNull(c.state.value.view)
    }

    @Test
    fun `a newer read wins over a slower older one`() = runTest {
        val slow = CompletableDeferred<Unit>()
        var reads = 0
        val c = controller {
            reads++
            if (reads == 1) {
                slow.await()
                listOf(drive("old", "2026-09-01T08:00:00Z"))
            } else {
                listOf(drive("a", "2026-10-01T08:00:00Z"), drive("b", "2026-10-02T08:00:00Z"))
            }
        }

        c.load()
        advanceUntilIdle()
        c.load()
        advanceUntilIdle()
        slow.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, c.state.value.view?.tripCount)
    }

    @Test
    fun `a result that arrives after reset is dropped and the next screen reads again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val c = controller {
            reads++
            if (reads == 1) gate.await()
            listOf(drive("a", "2026-10-01T08:00:00Z"))
        }
        c.ensureLoaded()
        advanceUntilIdle()

        c.reset()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(c.state.value.loading)
        assertNull(c.state.value.view)

        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, c.state.value.view?.tripCount)
    }
}
