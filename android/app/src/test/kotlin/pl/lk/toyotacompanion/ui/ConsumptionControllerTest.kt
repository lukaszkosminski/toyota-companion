package pl.lk.toyotacompanion.ui

import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** docs/SPEC.md §24: the consumption card reads the archive after the login and after a synchronization. */
class ConsumptionControllerTest {
    @Test
    fun `refresh reads the archive and ensureLoaded reads only once`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var reads = 0
        val c = ConsumptionController({ reads++; emptyList() }, { ZoneId.of("UTC") }, CoroutineScope(SupervisorJob() + io), io)
        c.ensureLoaded()
        advanceUntilIdle()
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, reads)
        assertNotNull(c.state.value)
        c.refresh()
        advanceUntilIdle()
        assertEquals(2, reads)
    }

    @Test
    fun `a failed read hides the card and reset forgets it`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val c = ConsumptionController({ throw IllegalStateException("db") }, { ZoneId.of("UTC") }, CoroutineScope(SupervisorJob() + io), io)
        c.refresh()
        advanceUntilIdle()
        assertNull(c.state.value)
        val ok = ConsumptionController({ emptyList() }, { ZoneId.of("UTC") }, CoroutineScope(SupervisorJob() + io), io)
        ok.refresh()
        advanceUntilIdle()
        ok.reset()
        assertNull(ok.state.value)
    }
}
