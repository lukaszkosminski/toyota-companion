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
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.sync.RefuelChange
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

private data class Call(val id: String, val liters: BigDecimal, val total: BigDecimal?, val full: Boolean)

/** The write behind "Popraw litry": records the calls and answers what the test decided. */
private class FakeAmend {
    var answer: RefuelChange = RefuelChange.Done(confirmed("r1"), 3)
    var error: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<Call>()

    suspend fun amend(id: String, liters: BigDecimal, total: BigDecimal?, full: Boolean): RefuelChange {
        calls += Call(id, liters, total, full)
        gate?.await()
        error?.let { throw it }
        return answer
    }
}

/**
 * docs/SPEC.md §15.2: the log is read on every navigation, a screen restored without
 * one reads once, and a logout drops a result that arrives late. The scope is the
 * application scope, so the tests drive it with the scheduler only.
 */
class RefuelLogControllerTest {

    private fun controller(read: FakeRead, io: CoroutineDispatcher, amend: FakeAmend = FakeAmend()) = RefuelLogController(
        read = { read.read() },
        amend = { id, liters, total, full -> amend.amend(id, liters, total, full) },
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
    )

    // ---------------- correction of a confirmed refuel (docs/SPEC.md §15.3) ----------------

    @Test
    fun `a correction is written and the log is read again`() = runTest {
        val read = FakeRead()
        val amend = FakeAmend()
        val controller = controller(read, StandardTestDispatcher(testScheduler), amend)
        controller.load()
        advanceUntilIdle()

        controller.amendLiters("r1", "30,5", "", filledToFull = true)
        advanceUntilIdle()

        assertEquals(listOf(Call("r1", BigDecimal("30.5"), null, true)), amend.calls)
        assertEquals(2, read.calls, "the economy card has to show the correction at once")
        assertEquals("r1", controller.state.value.amendedId)
        assertNull(controller.state.value.busyId)
        assertNull(controller.state.value.amendError)
    }

    @Test
    fun `the receipt total travels with the liters`() = runTest {
        val amend = FakeAmend()
        val controller = controller(FakeRead(), StandardTestDispatcher(testScheduler), amend)

        controller.amendLiters("r1", "30", "186,00", filledToFull = false)
        advanceUntilIdle()

        assertEquals(Call("r1", BigDecimal("30"), BigDecimal("186.00"), false), amend.calls.single())
    }

    @Test
    fun `missing or bad liters never reach the archive`() = runTest {
        val amend = FakeAmend()
        val controller = controller(FakeRead(), StandardTestDispatcher(testScheduler), amend)

        for (liters in listOf("", "0", "-3")) {
            controller.amendLiters("r1", liters, "", filledToFull = true)
            assertEquals(Texts.AMEND_LITERS_REQUIRED, controller.state.value.amendError, "liters '$liters'")
        }
        controller.amendLiters("r1", "30", "abc", filledToFull = true)
        assertEquals(Texts.REFUEL_NUMBER_ERROR, controller.state.value.amendError)
        advanceUntilIdle()

        assertTrue(amend.calls.isEmpty())
    }

    @Test
    fun `a refused correction shows the reason and does not read again`() = runTest {
        val read = FakeRead()
        val amend = FakeAmend().apply { answer = RefuelChange.NotConfirmed }
        val controller = controller(read, StandardTestDispatcher(testScheduler), amend)

        controller.amendLiters("r1", "30", "", filledToFull = true)
        advanceUntilIdle()

        assertEquals(Texts.AMEND_NOT_CONFIRMED, controller.state.value.amendError)
        assertEquals(0, read.calls)
        assertNull(controller.state.value.amendedId)
    }

    @Test
    fun `a database failure is shown without its text`() = runTest {
        val amend = FakeAmend().apply { error = IllegalStateException("disk full at /data/secret") }
        val controller = controller(FakeRead(), StandardTestDispatcher(testScheduler), amend)

        controller.amendLiters("r1", "30", "", filledToFull = true)
        advanceUntilIdle()

        assertEquals(Texts.REFUEL_SAVE_FAILED, controller.state.value.amendError)
        assertNull(controller.state.value.busyId)
    }

    @Test
    fun `a second correction while one is written is ignored`() = runTest {
        val amend = FakeAmend().apply { gate = CompletableDeferred() }
        val controller = controller(FakeRead(), StandardTestDispatcher(testScheduler), amend)

        controller.amendLiters("r1", "30", "", filledToFull = true)
        advanceUntilIdle()
        controller.amendLiters("r1", "31", "", filledToFull = true)
        assertEquals("r1", controller.state.value.busyId)

        requireNotNull(amend.gate).complete(Unit)
        advanceUntilIdle()
        assertEquals(1, amend.calls.size)
    }

    @Test
    fun `logout drops a correction that ends late`() = runTest {
        val amend = FakeAmend().apply { gate = CompletableDeferred() }
        val controller = controller(FakeRead(), StandardTestDispatcher(testScheduler), amend)
        controller.amendLiters("r1", "30", "", filledToFull = true)
        advanceUntilIdle()

        controller.reset()
        requireNotNull(amend.gate).complete(Unit)
        advanceUntilIdle()

        assertNull(controller.state.value.busyId, "busy must not survive the logout")
        assertNull(controller.state.value.amendedId)
    }

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
