package pl.lk.toyotacompanion.ui

import java.io.IOException
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
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException

/** docs/SPEC.md §42-§44: one read when a screen opens, typed outcomes, no late answer after logout. */
class RemoteDataControllerTest {

    private fun TestScope.controller(read: suspend () -> String?): RemoteDataController<String> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RemoteDataController(read, CoroutineScope(SupervisorJob() + dispatcher), dispatcher)
    }

    @Test
    fun `the screen starts loading and shows the data`() = runTest {
        val c = controller { "doors closed" }
        assertTrue(c.state.value.loading)

        c.ensureLoaded()
        advanceUntilIdle()

        assertEquals(RemoteDataUiState(loading = false, data = "doors closed"), c.state.value)
    }

    @Test
    fun `no payload is no data, not a failure`() = runTest {
        val c = controller { null }

        c.ensureLoaded()
        advanceUntilIdle()

        val state = c.state.value
        assertFalse(state.loading)
        assertNull(state.data)
        assertFalse(state.failed)
        assertFalse(state.unavailable)
    }

    @Test
    fun `403 and 404 mean the data is not available for this car`() = runTest {
        for (code in listOf(403, 404)) {
            val c = controller { throw ToyotaApiException(code, "gone") }
            c.ensureLoaded()
            advanceUntilIdle()
            assertEquals(RemoteDataUiState<String>(loading = false, unavailable = true), c.state.value, "HTTP $code")
        }
    }

    @Test
    fun `other errors are failures that a retry can fix`() = runTest {
        var calls = 0
        val c = controller {
            calls++
            when (calls) {
                1 -> throw ToyotaApiException(500, "boom")
                2 -> throw IOException("offline")
                else -> "ok"
            }
        }

        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(RemoteDataUiState<String>(loading = false, failed = true), c.state.value)

        c.load()
        advanceUntilIdle()
        assertEquals(RemoteDataUiState<String>(loading = false, failed = true), c.state.value)

        c.load()
        assertTrue(c.state.value.loading)
        advanceUntilIdle()
        assertEquals("ok", c.state.value.data)
        assertEquals(3, calls)
    }

    @Test
    fun `ensureLoaded reads once, load reads again`() = runTest {
        var calls = 0
        val c = controller { calls++; "x" }

        c.ensureLoaded()
        advanceUntilIdle()
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, calls)

        c.load()
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test
    fun `a failed retry keeps the last good data on screen`() = runTest {
        var calls = 0
        val c = controller {
            calls++
            if (calls == 1) "position" else throw ToyotaApiException(500, "boom")
        }
        c.ensureLoaded()
        advanceUntilIdle()

        c.load()
        advanceUntilIdle()

        assertEquals(RemoteDataUiState(loading = false, data = "position", failed = true), c.state.value)
    }

    @Test
    fun `an unavailable retry keeps the last good data too`() = runTest {
        var calls = 0
        val c = controller {
            calls++
            if (calls == 1) "doors" else throw ToyotaApiException(403, "gone")
        }
        c.ensureLoaded()
        advanceUntilIdle()

        c.load()
        advanceUntilIdle()

        assertEquals(RemoteDataUiState(loading = false, data = "doors", unavailable = true), c.state.value)
    }

    @Test
    fun `an honest empty answer drops the old data`() = runTest {
        var calls = 0
        val c = controller {
            calls++
            if (calls == 1) "doors" else null
        }
        c.ensureLoaded()
        advanceUntilIdle()

        c.load()
        advanceUntilIdle()

        assertEquals(RemoteDataUiState<String>(loading = false, data = null), c.state.value)
    }

    @Test
    fun `reset drops the data kept after a failure`() = runTest {
        var calls = 0
        val c = controller {
            calls++
            if (calls == 1) "position" else throw IOException("offline")
        }
        c.ensureLoaded()
        advanceUntilIdle()
        c.load()
        advanceUntilIdle()
        assertEquals("position", c.state.value.data)

        c.reset()

        assertEquals(RemoteDataUiState<String>(), c.state.value)
        assertNull(c.state.value.data)
    }

    @Test
    fun `an answer that arrives after reset is dropped`() = runTest {
        val gate = CompletableDeferred<String>()
        val c = controller { gate.await() }
        c.ensureLoaded()
        advanceUntilIdle()

        c.reset()
        gate.complete("old position")
        advanceUntilIdle()

        assertEquals(RemoteDataUiState<String>(), c.state.value)
        assertTrue(c.state.value.loading)
    }

    @Test
    fun `after reset the next screen reads again`() = runTest {
        var calls = 0
        val c = controller { calls++; "x" }
        c.ensureLoaded()
        advanceUntilIdle()

        c.reset()
        c.ensureLoaded()
        advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals("x", c.state.value.data)
    }
}
