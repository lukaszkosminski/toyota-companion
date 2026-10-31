package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

/** The archive behind the prompt: newest first, with the answers the test decided. */
private class FakePending {
    var list = listOf(refuel("r1"))
    var reads = 0
    var answer: RefuelChange = RefuelChange.Done(refuel("r1").copy(status = RefuelStatus.CONFIRMED), 3)
    var writeError: Exception? = null
    var lastPrice: BigDecimal? = null
    var confirmCalls = 0

    /** Holds a write open, like a synchronization that still holds the mutex. */
    var gate: CompletableDeferred<Unit>? = null

    suspend fun pending(): List<Refuel> {
        reads++
        return list
    }

    suspend fun confirm(id: String, price: BigDecimal): RefuelChange {
        confirmCalls++
        lastPrice = price
        gate?.await()
        writeError?.let { throw it }
        // The archive stops listing what it has taken (docs/SPEC.md §11.1).
        if (answer is RefuelChange.Done) list = list.filter { it.id != id }
        return answer
    }
}

/**
 * The prompt that follows a detected refuel (docs/SPEC.md §13.3): what it shows,
 * what "Później" silences, and what a write does to the queue of entries.
 */
class RefuelPromptControllerTest {

    private fun controller(
        fake: FakePending,
        dispatcher: CoroutineDispatcher,
        resolved: MutableList<String> = mutableListOf(),
        saved: MutableList<Unit> = mutableListOf(),
    ): RefuelPromptController = RefuelPromptController(
        pending = { fake.pending() },
        confirmRefuel = { id, price -> fake.confirm(id, price) },
        onResolved = { id -> resolved.add(id) },
        onSaved = { saved.add(Unit) },
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        io = dispatcher,
    )

    @Test
    fun `opening shows the newest entry and cancels its notification`() = runTest {
        val fake = FakePending()
        val resolved = mutableListOf<String>()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler), resolved)

        prompt.ensureLoaded()
        advanceUntilIdle()

        assertEquals("r1", requireNotNull(prompt.state.value.entry).id)
        assertEquals(listOf("r1"), resolved, "the notification is not needed when the dialog is here")
    }

    @Test
    fun `ensureLoaded reads once and refresh always reads`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))

        prompt.ensureLoaded()
        advanceUntilIdle()
        // LaunchedEffect(loggedIn) starts again when the Activity is recreated.
        prompt.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, fake.reads, "a rotation does not read the database again")

        prompt.refresh()
        advanceUntilIdle()
        assertEquals(2, fake.reads, "a synchronization does read again")
    }

    @Test
    fun `later silences this entry only and a newer one still asks`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.ensureLoaded()
        advanceUntilIdle()

        prompt.later()
        advanceUntilIdle()
        assertNull(prompt.state.value.entry)

        // The whole point of F2c: the next detected refuel asks again in the same process.
        fake.list = listOf(refuel("r2"), refuel("r1"))
        prompt.refresh()
        advanceUntilIdle()

        assertEquals("r2", prompt.state.value.entry?.id)
    }

    @Test
    fun `saving writes the parsed price and asks about the next entry`() = runTest {
        val fake = FakePending().apply {
            list = listOf(refuel("r1"), refuel("r2"))
            answer = RefuelChange.Done(refuel("r1").copy(status = RefuelStatus.CONFIRMED), 3)
        }
        val resolved = mutableListOf<String>()
        val saved = mutableListOf<Unit>()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler), resolved, saved)
        prompt.ensureLoaded()
        advanceUntilIdle()

        prompt.priceChanged("6,19")
        prompt.save()
        advanceUntilIdle()

        assertEquals(0, BigDecimal("6.19").compareTo(requireNotNull(fake.lastPrice)))
        assertEquals(1, fake.confirmCalls)
        assertTrue("r1" in resolved && resolved.last() == "r2", "cancelled: $resolved")
        assertEquals(1, saved.size, "session.reload after a write (docs/SPEC.md §13.3)")
        assertEquals("r2", prompt.state.value.entry?.id, "the next waiting entry")
        assertEquals("", prompt.state.value.priceText, "the field of the next question is empty")
    }

    @Test
    fun `a bad value stays in the window and never reaches the archive`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.ensureLoaded()
        advanceUntilIdle()

        prompt.priceChanged("")
        prompt.save()
        advanceUntilIdle()

        assertEquals(Texts.REFUEL_COMBINATION_ERROR, prompt.state.value.error)
        assertEquals("r1", prompt.state.value.entry?.id, "the window stays")
        assertEquals(0, fake.confirmCalls)
    }

    @Test
    fun `an entry that stopped waiting is skipped without a message`() = runTest {
        val fake = FakePending().apply {
            list = listOf(refuel("r1"), refuel("r2"))
            answer = RefuelChange.NotPending
        }
        val resolved = mutableListOf<String>()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler), resolved)
        prompt.ensureLoaded()
        advanceUntilIdle()

        prompt.priceChanged("6,19")
        prompt.save()
        advanceUntilIdle()

        assertNull(prompt.state.value.error)
        assertEquals("r2", prompt.state.value.entry?.id)
        assertTrue(resolved.last() == "r2" && "r1" in resolved, "its notification is gone too: $resolved")
    }

    @Test
    fun `a failing write keeps the entry and shows the save error`() = runTest {
        val fake = FakePending().apply { writeError = IllegalStateException("database is busy") }
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.ensureLoaded()
        advanceUntilIdle()

        prompt.priceChanged("6,19")
        prompt.save()
        advanceUntilIdle()

        assertEquals(Texts.REFUEL_SAVE_FAILED, prompt.state.value.error)
        assertEquals("r1", prompt.state.value.entry?.id)
        assertTrue(!prompt.state.value.busy)
    }

    @Test
    fun `a second click while saving writes once`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.ensureLoaded()
        advanceUntilIdle()

        prompt.priceChanged("6,19")
        prompt.save()
        prompt.save()
        advanceUntilIdle()

        assertEquals(1, fake.confirmCalls)
    }

    @Test
    fun `more asks for the full form and the request is spent after navigation`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.ensureLoaded()
        advanceUntilIdle()

        prompt.more()
        advanceUntilIdle()

        assertEquals("r1", requireNotNull(prompt.state.value.formRequest).id)
        prompt.formRequestHandled()
        assertNull(prompt.state.value.formRequest)
    }

    @Test
    fun `a notification opens its own entry and an answered one opens nothing`() = runTest {
        val fake = FakePending().apply { list = listOf(refuel("r1"), refuel("r2")) }
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))

        // Unknown yet: the list has to be read first.
        prompt.openFromNotification("r2")
        advanceUntilIdle()
        assertEquals("r2", requireNotNull(prompt.state.value.formRequest).id)

        prompt.formRequestHandled()
        prompt.openFromNotification("answered-elsewhere")
        advanceUntilIdle()
        assertNull(prompt.state.value.formRequest)
    }

    @Test
    fun `logging out empties the window and forgets later`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.ensureLoaded()
        advanceUntilIdle()
        prompt.priceChanged("6,19")
        prompt.later()
        advanceUntilIdle()
        assertNull(prompt.state.value.entry)

        prompt.reset()
        prompt.ensureLoaded()
        advanceUntilIdle()

        assertEquals("r1", prompt.state.value.entry?.id, "a new login asks again (docs/SPEC.md §13.3)")
        assertEquals("", prompt.state.value.priceText, "the typed text is gone with the session")
    }

    @Test
    fun `a form on screen keeps the window away`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.onFormScreen = { true }

        prompt.ensureLoaded()
        advanceUntilIdle()

        assertNull(prompt.state.value.entry)
        assertNotNull(prompt.state.value)
    }

    @Test
    fun `screenChanged before the first read does not read the database`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))

        // A navigation callback that arrives before the login read must stay free (SPEC 13.3).
        prompt.screenChanged()
        advanceUntilIdle()

        assertEquals(0, fake.reads, "nothing is read before the first read")
        assertTrue(prompt.state.value.loading, "the read is still unknown")
        assertNull(prompt.state.value.entry)
    }

    @Test
    fun `screenChanged decides again from the last read`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.ensureLoaded()
        advanceUntilIdle()

        // A navigation, and a rotation whose effect starts again: no database, twice (SPEC 13.3).
        prompt.screenChanged()
        prompt.screenChanged()
        advanceUntilIdle()

        assertEquals(1, fake.reads, "a screen change does not read the database again")
        assertEquals("r1", prompt.state.value.entry?.id, "the same entry stays on screen")
    }

    @Test
    fun `a form coming and going moves the window without another read`() = runTest {
        val fake = FakePending()
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        var onForm = false
        prompt.onFormScreen = { onForm }
        prompt.ensureLoaded()
        advanceUntilIdle()
        assertEquals("r1", prompt.state.value.entry?.id)

        onForm = true
        prompt.screenChanged()
        assertNull(prompt.state.value.entry, "the window steps back from a form")

        onForm = false
        prompt.screenChanged()
        assertEquals("r1", prompt.state.value.entry?.id, "the question returns with the list")
        assertEquals(1, fake.reads, "both moves came from the cached read")
    }

    @Test
    fun `an entry answered on another screen leaves the cache and shows the next one`() = runTest {
        val fake = FakePending().apply { list = listOf(refuel("a"), refuel("b")) }
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.ensureLoaded()
        advanceUntilIdle()
        assertEquals("a", prompt.state.value.entry?.id, "the newest entry first")

        // The full form saved the price of "a": the controller is told, the archive is not read.
        prompt.resolvedElsewhere("a")
        assertNull(prompt.state.value.entry, "the answered question disappears at once")

        prompt.screenChanged()
        advanceUntilIdle()

        assertEquals("b", prompt.state.value.entry?.id, "the next waiting entry from the same read")
        assertEquals(1, fake.reads, "no read between the two questions")
    }

    @Test
    fun `an entry answered elsewhere during our write does not close the window`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakePending().apply { this.gate = gate }
        val prompt = controller(fake, StandardTestDispatcher(testScheduler))
        prompt.ensureLoaded()
        advanceUntilIdle()

        prompt.priceChanged("6,19")
        prompt.save()
        advanceUntilIdle()
        assertTrue(prompt.state.value.busy, "the write is held open by the gate")

        prompt.resolvedElsewhere("r1")
        assertEquals("r1", prompt.state.value.entry?.id, "our own dialog stays until the write reports")

        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(prompt.state.value.entry, "the finished write closes it (SPEC 13.3)")
    }
}
