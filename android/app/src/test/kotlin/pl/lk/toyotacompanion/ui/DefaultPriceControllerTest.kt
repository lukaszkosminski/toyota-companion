package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
import pl.lk.toyotacompanion.core.sync.CostRecompute

/** The settings file and the recount behind the controller: counts calls, answers what the test decides. */
private class FakeSettings {
    var price: BigDecimal? = null
    var readError: Exception? = null
    var writeError: Exception? = null
    var reads = 0
    val writes = mutableListOf<BigDecimal?>()

    /** Holds a write open, like a recount that still waits for the synchronization mutex. */
    var gate: CompletableDeferred<Unit>? = null

    var recomputeResult: CostRecompute = CostRecompute.Done(3)
    var recomputeCalls = 0
    var recomputeError: Exception? = null

    suspend fun read(): BigDecimal? {
        reads++
        readError?.let { throw it }
        return price
    }

    suspend fun persist(value: BigDecimal?) {
        gate?.await()
        writeError?.let { throw it }
        price = value
        writes += value
    }

    suspend fun recompute(): CostRecompute {
        recomputeCalls++
        recomputeError?.let { throw it }
        return recomputeResult
    }
}

/**
 * docs/SPEC.md §12.3: the write of the setting, the recount behind it and what a
 * late result may still change. The scope is the application scope, so the tests
 * drive it with the scheduler only.
 */
class DefaultPriceControllerTest {
    private fun controller(settings: FakeSettings, io: CoroutineDispatcher) = DefaultPriceController(
        read = { settings.read() },
        persist = { settings.persist(it) },
        recompute = { settings.recompute() },
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
    )

    @Test
    fun `opening without a loaded price reads it`() = runTest {
        val settings = FakeSettings().apply { price = BigDecimal("6.19") }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))

        controller.open()
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(!state.loading, "ekran nie moze czekac w nieskonczonosc")
        assertEquals(0, BigDecimal("6.19").compareTo(requireNotNull(state.price)))
    }

    @Test
    fun `a failing read is shown as a failure`() = runTest {
        val settings = FakeSettings().apply { readError = IllegalStateException("file is gone") }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))

        controller.open()
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state.loadFailed && state.price == null, "got $state")
        assertNull(state.error, "tresc wyjatku nie trafia do UI")
    }

    @Test
    fun `saves the price and recomputes once`() = runTest {
        val settings = FakeSettings()
        val controller = controller(settings, StandardTestDispatcher(testScheduler))
        controller.open()
        advanceUntilIdle()

        controller.save("6,19")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf<BigDecimal?>(BigDecimal("6.190")), settings.writes)
        assertEquals(1, settings.recomputeCalls)
        assertEquals(0, BigDecimal("6.190").compareTo(requireNotNull(state.price)))
        assertTrue(state.saved && !state.busy && state.error == null, "got $state")
    }

    @Test
    fun `removing writes null and recomputes`() = runTest {
        val settings = FakeSettings().apply { price = BigDecimal("6.19") }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))
        controller.open()
        advanceUntilIdle()

        controller.remove()
        advanceUntilIdle()

        assertEquals(listOf<BigDecimal?>(null), settings.writes)
        assertEquals(1, settings.recomputeCalls)
        assertNull(controller.state.value.price)
        assertTrue(controller.state.value.saved)
    }

    @Test
    fun `a bad field never reaches the settings file`() = runTest {
        val settings = FakeSettings()
        val controller = controller(settings, StandardTestDispatcher(testScheduler))

        controller.save("abc")
        advanceUntilIdle()

        assertTrue(settings.writes.isEmpty(), "brak zapisu przed walidacja")
        assertEquals(0, settings.recomputeCalls)
        assertEquals(Texts.REFUEL_NUMBER_ERROR, controller.state.value.error)
    }

    @Test
    fun `a failing write shows the save error`() = runTest {
        val settings = FakeSettings().apply { writeError = IllegalStateException("disk is full") }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))

        controller.save("6,19")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(Texts.REFUEL_SAVE_FAILED, state.error)
        assertTrue("full" !in state.error.orEmpty(), "tresc wyjatku nie trafia do UI")
        assertTrue(!state.busy && !state.saved)
        assertEquals(0, settings.recomputeCalls, "bez zapisu nie ma czego przeliczac")
    }

    @Test
    fun `no vehicle keeps the price without an error`() = runTest {
        val settings = FakeSettings().apply { recomputeResult = CostRecompute.NoVehicle }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))

        controller.save("6,19")
        advanceUntilIdle()

        val state = controller.state.value
        assertNull(state.error, "brak pojazdu to nie blad ustawienia (SPEC §12.3)")
        assertTrue(state.saved)
        assertEquals(0, BigDecimal("6.190").compareTo(requireNotNull(state.price)))
    }

    @Test
    fun `a failing recount keeps the saved price`() = runTest {
        val settings = FakeSettings().apply { recomputeError = IllegalStateException("database is closed") }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))

        controller.save("6,19")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(1, settings.writes.size)
        assertNull(state.error, "najblizsza synchronizacja przeliczy koszty")
        assertTrue(state.saved)
    }

    @Test
    fun `a second click while saving writes once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val settings = FakeSettings().apply { this.gate = gate }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))

        controller.save("6,19")
        advanceUntilIdle()
        assertTrue(controller.state.value.busy)

        controller.save("6,25")
        controller.remove()
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, settings.writes.size, "przyciski sa zablokowane na czas zapisu")
        assertEquals(1, settings.recomputeCalls)
        assertTrue(controller.state.value.saved)
    }

    @Test
    fun `logging out drops the late result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val settings = FakeSettings().apply { this.gate = gate }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))
        controller.open()
        advanceUntilIdle()

        controller.save("6,19")
        advanceUntilIdle()
        controller.reset()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, settings.writes.size, "preferencja telefonu zostaje zapisana")
        assertEquals(DefaultPriceUiState(), controller.state.value, "pozny wynik nie przywraca stanu")
    }

    @Test
    fun `ensureLoaded does not read the file again after a good read`() = runTest {
        val settings = FakeSettings().apply { price = BigDecimal("6.19") }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))

        controller.ensureLoaded()
        advanceUntilIdle()
        assertEquals(0, BigDecimal("6.19").compareTo(requireNotNull(controller.state.value.price)))

        // LaunchedEffect(loggedIn) starts again on every Activity recreate (rotation).
        controller.ensureLoaded()
        controller.ensureLoaded()
        advanceUntilIdle()

        assertEquals(1, settings.reads, "obrót ekranu nie czyta pliku preferencji ponownie")
    }

    @Test
    fun `ensureLoaded retries after a failed read`() = runTest {
        val settings = FakeSettings().apply { readError = IllegalStateException("file is gone") }
        val controller = controller(settings, StandardTestDispatcher(testScheduler))

        controller.ensureLoaded()
        advanceUntilIdle()
        assertTrue(controller.state.value.loadFailed)

        settings.price = BigDecimal("6.19")
        settings.readError = null
        controller.ensureLoaded()
        advanceUntilIdle()

        assertEquals(0, BigDecimal("6.19").compareTo(requireNotNull(controller.state.value.price)))
        assertEquals(2, settings.reads, "po nieudanym odczycie mozna sprobowac ponownie")
    }

}
