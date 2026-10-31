package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.sync.RefuelLog

private val TS: Instant = Instant.parse("2026-10-29T13:00:00Z")

private fun confirmed(id: String): Refuel = Refuel(
    id = id,
    ts = TS,
    odometerKm = 12_345.0,
    levelBefore = 20,
    levelAfter = 90,
    liters = BigDecimal("30.000"),
    pricePerLiter = BigDecimal("6.190"),
    totalCost = BigDecimal("185.70"),
    status = RefuelStatus.CONFIRMED,
    litersSource = LitersSource.RECEIPT,
    filledToFull = true,
)

/** The archive behind the controller: counts the reads and answers what the test decided. */
private class FakeRead {
    var log = RefuelLog(listOf(confirmed("r1")), emptyList())
    var error: Exception? = null

    /** Holds the read open, like a synchronization that still holds the mutex. */
    var gate: CompletableDeferred<Unit>? = null
    var calls = 0

    suspend fun read(): RefuelLog {
        calls++
        gate?.await()
        error?.let { throw it }
        return log
    }
}

/**
 * docs/SPEC.md §15.2: the log is read on every navigation, a screen restored without
 * one reads once, and a logout drops a result that arrives late. The scope is the
 * application scope, so the tests drive it with the scheduler only.
 */
class RefuelLogControllerTest {

    private fun controller(read: FakeRead, io: CoroutineDispatcher) = RefuelLogController(
        read = { read.read() },
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
    )

    @Test
    fun `load publishes the log of the selected vehicle`() = runTest {
        val read = FakeRead()
        val controller = controller(read, StandardTestDispatcher(testScheduler))

        controller.load()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf("r1"), state.log.refuels.map { it.id })
        assertTrue(!state.loading && !state.loadFailed)
        assertEquals(1, read.calls)
    }

    @Test
    fun `a failing read is shown as a failure without the exception text`() = runTest {
        val read = FakeRead().apply { error = IllegalStateException("room database is closed") }
        val controller = controller(read, StandardTestDispatcher(testScheduler))

        controller.load()
        advanceUntilIdle()

        val state = controller.state.value
        // The whole state: there is no field that could carry the message (docs/SECURITY.md).
        assertEquals(RefuelLogUiState(loading = false, loadFailed = true), state)
        assertFalse(state.toString().contains("closed"), state.toString())
    }

    @Test
    fun `ensureLoaded reads once and only when load never ran`() = runTest {
        val read = FakeRead()
        val controller = controller(read, StandardTestDispatcher(testScheduler))

        controller.ensureLoaded()
        advanceUntilIdle()
        controller.ensureLoaded()
        advanceUntilIdle()

        assertEquals(1, read.calls)
        assertEquals(listOf("r1"), controller.state.value.log.refuels.map { it.id })
    }

    @Test
    fun `opening the screen again reads again`() = runTest {
        val read = FakeRead()
        val controller = controller(read, StandardTestDispatcher(testScheduler))

        controller.load()
        advanceUntilIdle()
        // The user opens the refuel prompt, confirms a price, and navigates back to the log.
        read.log = RefuelLog(listOf(confirmed("r1"), confirmed("r2")), emptyList())
        controller.load()
        advanceUntilIdle()

        assertEquals(2, read.calls)
        assertEquals(listOf("r1", "r2"), controller.state.value.log.refuels.map { it.id })
    }

    @Test
    fun `reset during a read drops the late result`() = runTest {
        val read = FakeRead().apply { gate = CompletableDeferred() }
        val controller = controller(read, StandardTestDispatcher(testScheduler))

        controller.load()
        advanceUntilIdle()
        assertEquals(1, read.calls, "the read started and is still open")

        controller.reset()
        assertEquals(RefuelLogUiState(), controller.state.value, "logout forgets the old account")

        read.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(RefuelLogUiState(), controller.state.value, "the late log of the old account is dropped")

        // The next account must not be left with an empty screen.
        controller.ensureLoaded()
        advanceUntilIdle()
        assertEquals(2, read.calls)
        assertEquals(listOf("r1"), controller.state.value.log.refuels.map { it.id })
    }
}
