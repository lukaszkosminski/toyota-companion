package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
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
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.sync.RefuelChange

private val TS: Instant = Instant.parse("2026-10-29T13:00:00Z")

private fun refuel(id: String): Refuel = Refuel(
    id = id,
    ts = TS,
    odometerKm = 12_345.0,
    levelBefore = 20,
    levelAfter = 80,
    liters = null,
    pricePerLiter = null,
    totalCost = null,
    status = RefuelStatus.PENDING,
)

/** The archive behind the controller: counts every call and answers what the test decided. */
private class FakeWrites {
    var list = listOf(refuel("r1"), refuel("r2"))
    var listError: Exception? = null
    var listCalls = 0
    var answer: RefuelChange = RefuelChange.Done(refuel("r1").copy(status = RefuelStatus.CONFIRMED), 3)
    var confirmCalls = 0
    var dismissCalls = 0
    var lastPrice: BigDecimal? = null
    var lastFilledToFull: Boolean? = null

    /** Holds a write open, like a synchronization that still holds the mutex. */
    var gate: CompletableDeferred<Unit>? = null

    suspend fun pending(): List<Refuel> {
        listCalls++
        listError?.let { throw it }
        return list
    }

    /** Symuluje baze, ktora rzuca wyjatek w trakcie zapisu. */
    var writeError: Exception? = null

    suspend fun confirm(
        id: String,
        pricePerLiter: BigDecimal?,
        liters: BigDecimal?,
        totalCost: BigDecimal?,
        filledToFull: Boolean?,
    ): RefuelChange {
        confirmCalls++
        lastPrice = pricePerLiter
        lastFilledToFull = filledToFull
        gate?.await()
        writeError?.let { throw it }
        list = emptyList()
        return answer
    }

    suspend fun dismiss(id: String): RefuelChange {
        dismissCalls++
        gate?.await()
        list = emptyList()
        return answer
    }
}

/**
 * docs/SPEC.md §11.3: the list, the guarded write and what a late result may still
 * change. The scope of the controller is the application scope, so the tests drive
 * it with the scheduler only.
 */
class RefuelControllerTest {
    private fun controller(writes: FakeWrites, io: CoroutineDispatcher) = RefuelController(
        pending = { writes.pending() },
        confirmRefuel = { id, price, liters, total, filled -> writes.confirm(id, price, liters, total, filled) },
        dismissRefuel = { id -> writes.dismiss(id) },
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
    )

    @Test
    fun `reload lists the waiting refuels`() = runTest {
        val writes = FakeWrites()
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        controller.reload()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf("r1", "r2"), state.refuels.map { it.id })
        assertTrue(!state.loading && !state.loadFailed)
    }

    @Test
    fun `a failing read is shown as a failure`() = runTest {
        val writes = FakeWrites().apply { listError = IllegalStateException("database is closed") }
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        controller.reload()
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state.loadFailed && state.refuels.isEmpty(), "got $state")
    }

    @Test
    fun `saving a price writes once, reloads and marks the entry saved`() = runTest {
        val writes = FakeWrites()
        val controller = controller(writes, StandardTestDispatcher(testScheduler))
        controller.reload()
        advanceUntilIdle()

        controller.confirm("r1", "6,19", "", "", filledToFull = false)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(1, writes.confirmCalls)
        assertEquals(0, BigDecimal("6.19").compareTo(requireNotNull(writes.lastPrice)))
        assertEquals("r1", state.savedId)
        assertNull(state.busyId)
        assertNull(state.formError)
        assertEquals(2, writes.listCalls, "the list is read again after a write")
        assertTrue(state.refuels.isEmpty(), "the entry left the waiting list")
    }

    @Test
    fun `the filled-to-full switch is dropped when there are no liters`() = runTest {
        val writes = FakeWrites()
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        controller.confirm("r1", "6,19", "", "", filledToFull = true)
        advanceUntilIdle()

        assertNull(writes.lastFilledToFull, "the domain would refuse the flag without liters")
    }

    @Test
    fun `a form that fails the check never reaches the database`() = runTest {
        val writes = FakeWrites()
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        controller.confirm("r1", "", "25,8", "", filledToFull = false)
        advanceUntilIdle()

        assertEquals(0, writes.confirmCalls)
        assertEquals(Texts.REFUEL_COMBINATION_ERROR, controller.state.value.formError)
    }

    @Test
    fun `a refusal by the domain shows the write error`() = runTest {
        val writes = FakeWrites().apply { answer = RefuelChange.Invalid }
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        controller.confirm("r1", "6,19", "", "", filledToFull = false)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(Texts.REFUEL_WRITE_ERROR, state.formError)
        assertNull(state.savedId)
        assertNull(state.busyId)
    }

    @Test
    fun `an entry that is gone shows the stale message`() = runTest {
        val writes = FakeWrites().apply { answer = RefuelChange.NotPending }
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        controller.confirm("r1", "6,19", "", "", filledToFull = false)
        advanceUntilIdle()

        assertEquals(Texts.REFUEL_STALE_ENTRY, controller.state.value.formError)
    }

    @Test
    fun `a second click while saving writes once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writes = FakeWrites().apply { this.gate = gate }
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        controller.confirm("r1", "6,19", "", "", filledToFull = false)
        advanceUntilIdle()
        assertEquals("r1", controller.state.value.busyId)

        controller.confirm("r1", "6,25", "", "", filledToFull = false)
        controller.dismiss("r1")
        advanceUntilIdle()

        assertEquals(1, writes.confirmCalls, "the buttons are blocked during a write")
        assertEquals(0, writes.dismissCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, writes.confirmCalls)
        assertEquals("r1", controller.state.value.savedId)
    }

    @Test
    fun `logging out during a write drops the late result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writes = FakeWrites().apply { this.gate = gate }
        val controller = controller(writes, StandardTestDispatcher(testScheduler))
        controller.reload()
        advanceUntilIdle()
        assertEquals(2, controller.state.value.refuels.size)

        controller.confirm("r1", "6,19", "", "", filledToFull = false)
        advanceUntilIdle()
        controller.reset()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, writes.confirmCalls, "the write itself is not cancelled")
        assertEquals(RefuelUiState(), controller.state.value, "a late result may not resurrect the list")
    }

    @Test
    fun `dismiss writes the entry away and reloads`() = runTest {
        val writes = FakeWrites().apply {
            answer = RefuelChange.Done(refuel("r1").copy(status = RefuelStatus.DISMISSED), 3)
        }
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        controller.dismiss("r1")
        advanceUntilIdle()

        assertEquals(1, writes.dismissCalls)
        assertEquals("r1", controller.state.value.savedId)
        assertTrue(controller.state.value.refuels.isEmpty())
    }

    @Test
    fun `opening a form without a loaded list reads it`() = runTest {
        val writes = FakeWrites()
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        // Po smierci procesu formularz jest pierwszym kompozablem: nikt nie wolal reload().
        controller.openForm("r1")
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(!state.loading, "formularz nie moze czekac w nieskonczonosc")
        assertEquals(listOf("r1", "r2"), state.refuels.map { it.id })
    }

    @Test
    fun `a stale entry shows the message and reloads the list`() = runTest {
        val writes = FakeWrites().apply { answer = RefuelChange.NotFound }
        val controller = controller(writes, StandardTestDispatcher(testScheduler))
        controller.reload()
        advanceUntilIdle()
        assertEquals(2, controller.state.value.refuels.size)

        controller.confirm("r1", "6,19", "", "", filledToFull = false)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(Texts.REFUEL_STALE_ENTRY, state.formError)
        assertNull(state.savedId)
        assertEquals(2, writes.listCalls, "zniknety wpis znika rowniez z listy")
    }

    @Test
    fun `a failing database has its own message`() = runTest {
        val writes = FakeWrites().apply { writeError = IllegalStateException("database is closed") }
        val controller = controller(writes, StandardTestDispatcher(testScheduler))

        controller.confirm("r1", "6,19", "", "", filledToFull = false)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(Texts.REFUEL_SAVE_FAILED, state.formError)
        assertTrue("closed" !in requireNotNull(state.formError), "tresc wyjatku nie trafia do UI")
        assertNull(state.savedId)
        assertEquals(0, writes.listCalls, "bez udanego zapisu lista zostaje jak byla")
    }

}
