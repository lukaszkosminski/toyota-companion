package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
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

private fun trip(id: String, start: String, km: Double, fuel: String, ev: Double? = null) = StoredTrip(
    TripRecord(Trip(id, Instant.parse(start), Instant.parse(start).plusSeconds(3_600), km, BigDecimal(fuel), ev), "VINAAA111"),
    null,
)

/** The settings and the archive behind the controller. */
private class Fake {
    var goals = EcoGoals()
    var saved = 0
    var trips = listOf(trip("a", "2026-10-05T10:00:00Z", 100.0, "4.3", ev = 42.0))
    var error: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var reads = 0
    var savedNotifications = 0

    suspend fun readTrips(): List<StoredTrip> {
        reads++
        gate?.await()
        error?.let { throw it }
        return trips
    }
}

/** docs/SPEC.md §40: the controller of the eco goals on fake settings and a fake archive. */
class EcoGoalsControllerTest {
    private fun TestScope.controller(fake: Fake): EcoGoalsController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return EcoGoalsController(
            readGoals = { fake.goals },
            saveGoals = { fake.goals = it; fake.saved++ },
            readTrips = fake::readTrips,
            zone = { WARSAW },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
            today = { LocalDate.parse("2026-10-20") },
            onSaved = { fake.savedNotifications++ },
        )
    }

    @Test
    fun `without a goal there are no figures`() = runTest {
        val c = controller(Fake())
        c.ensureLoaded()
        advanceUntilIdle()
        assertFalse(c.state.value.goals.any)
        assertNull(c.state.value.progress)
    }

    @Test
    fun `stored goals are read and the month is computed once`() = runTest {
        val fake = Fake().apply { goals = EcoGoals(BigDecimal("4.50"), 40) }
        val c = controller(fake)
        c.ensureLoaded()
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, fake.reads)
        val progress = requireNotNull(c.state.value.progress)
        assertEquals(true, progress.current.consumption?.met)
        assertEquals(true, progress.current.ev?.met)
    }

    @Test
    fun `valid goals are stored, shown and announced`() = runTest {
        val fake = Fake()
        val c = controller(fake)
        assertTrue(c.set("4,5", "40"))
        advanceUntilIdle()
        assertEquals(EcoGoals(BigDecimal("4.50"), 40), fake.goals)
        assertEquals(EcoGoals(BigDecimal("4.50"), 40), c.state.value.goals)
        assertEquals(1, fake.savedNotifications)
        assertEquals(true, c.state.value.progress?.current?.consumption?.met)
    }

    @Test
    fun `an empty field removes only its goal`() = runTest {
        val fake = Fake().apply { goals = EcoGoals(BigDecimal("4.50"), 40) }
        val c = controller(fake)
        assertTrue(c.set("", "40"))
        advanceUntilIdle()
        assertEquals(EcoGoals(null, 40), fake.goals)
        assertTrue(c.set("", ""))
        advanceUntilIdle()
        assertFalse(c.state.value.goals.any)
        assertNull(c.state.value.progress)
    }

    @Test
    fun `a wrong value in either field changes nothing`() = runTest {
        val fake = Fake().apply { goals = EcoGoals(BigDecimal("4.50"), 40) }
        val c = controller(fake)
        for ((consumption, ev) in listOf("0" to "40", "21" to "40", "abc" to "40", "4,5" to "0", "4,5" to "101", "4,5" to "x")) {
            assertFalse(c.set(consumption, ev), "$consumption / $ev")
        }
        advanceUntilIdle()
        assertEquals(0, fake.saved)
        assertEquals(0, fake.savedNotifications)
        assertEquals(EcoGoals(BigDecimal("4.50"), 40), fake.goals)
    }

    @Test
    fun `a failed read of the trips keeps the goals and hides the figures`() = runTest {
        val fake = Fake().apply {
            goals = EcoGoals(BigDecimal("4.50"))
            error = IllegalStateException("unreadable")
        }
        val c = controller(fake)
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(EcoGoals(BigDecimal("4.50")), c.state.value.goals)
        assertNull(c.state.value.progress)
    }

    @Test
    fun `a refresh after a synchronization sees the new trips`() = runTest {
        val fake = Fake().apply { goals = EcoGoals(BigDecimal("4.50")) }
        val c = controller(fake)
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(BigDecimal("4.30"), c.state.value.progress?.current?.consumption?.actual)

        fake.trips = fake.trips + trip("b", "2026-10-06T10:00:00Z", 100.0, "5.7")
        c.refresh()
        advanceUntilIdle()

        assertEquals(BigDecimal("5.00"), c.state.value.progress?.current?.consumption?.actual)
        assertEquals(false, c.state.value.progress?.current?.consumption?.met)
    }

    @Test
    fun `reset forgets the figures and drops a read that was still running`() = runTest {
        val fake = Fake().apply {
            goals = EcoGoals(BigDecimal("4.50"))
            gate = CompletableDeferred()
        }
        val c = controller(fake)
        c.refresh()
        advanceUntilIdle()

        c.reset()
        fake.gate?.complete(Unit)
        advanceUntilIdle()

        assertFalse(c.state.value.goals.any)
        assertNull(c.state.value.progress)
        // After a reset the next screen load reads again.
        fake.gate = null
        c.ensureLoaded()
        advanceUntilIdle()
        assertTrue(c.state.value.goals.any)
    }
}
