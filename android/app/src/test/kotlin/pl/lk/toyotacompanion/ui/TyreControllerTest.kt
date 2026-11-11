package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.TyreSet
import pl.lk.toyotacompanion.core.domain.Trip

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun trip(id: String, start: String, km: Double) = StoredTrip(
    TripRecord(Trip(id, Instant.parse(start), Instant.parse(start).plusSeconds(1_800), km, BigDecimal("1.0"), null), "VINAAA111"),
    null,
)

/** The phone settings and the archive behind the controller: plain text, as the app keeps them. */
private class FakeTyreSettings {
    var changes: String? = null
    var thresholds: String? = null
    var keys: String? = null
    var savedChanges = 0
    var savedKeys = 0
    var trips = listOf(trip("a", "2026-10-05T10:00:00Z", 120.0), trip("b", "2026-10-20T10:00:00Z", 80.0))
    var error: Exception? = null

    suspend fun readTrips(): List<StoredTrip> {
        error?.let { throw it }
        return trips
    }
}

/** docs/SPEC.md §56: the controller of the tyre card on fake settings and a fake archive (C41). */
class TyreControllerTest {
    private fun TestScope.controller(fake: FakeTyreSettings): TyreController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return TyreController(
            readTrips = fake::readTrips,
            readChangesText = { fake.changes },
            saveChangesText = { fake.changes = it; fake.savedChanges++ },
            readThresholdsText = { fake.thresholds },
            saveThresholdsText = { fake.thresholds = it },
            readKeysText = { fake.keys },
            saveKeysText = { fake.keys = it; fake.savedKeys++ },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
            zone = { WARSAW },
        )
    }

    @Test
    fun `a first fitting is saved and its mileage comes from the archive`() = runTest {
        val fake = FakeTyreSettings()
        val c = controller(fake)
        c.load()
        advanceUntilIdle()
        assertEquals(TyreCardMode.INVITE, tyreCardMode(c.state.value), "no fitting yet: the card invites one")

        assertNull(c.addChange(LocalDate.parse("2026-10-01"), TyreSet.SUMMER, "48000"))
        advanceUntilIdle()

        assertTrue(fake.changes!!.contains("2026-10-01"))
        assertEquals(1, fake.savedChanges)
        val usage = c.state.value.usage
        assertEquals(TyreSet.SUMMER, usage?.sets?.lastOrNull()?.change?.set)
        assertEquals(200.0, usage?.sets?.lastOrNull()?.km?.toDouble())
        assertEquals(TyreCardMode.SETS, tyreCardMode(c.state.value))
    }

    @Test
    fun `a wrong odometer, a future date, the same day and a day before the last fitting are refused`() = runTest {
        val fake = FakeTyreSettings()
        val c = controller(fake)
        assertNull(c.addChange(LocalDate.parse("2026-10-01"), TyreSet.SUMMER, ""))
        advanceUntilIdle()

        assertEquals(TyreFormError.ODOMETER, c.addChange(LocalDate.parse("2026-10-02"), TyreSet.WINTER, "abc"))
        assertEquals(TyreFormError.FUTURE, c.addChange(LocalDate.parse("2099-01-01"), TyreSet.WINTER, ""))
        assertEquals(TyreFormError.DUPLICATE, c.addChange(LocalDate.parse("2026-10-01"), TyreSet.WINTER, ""))
        assertEquals(TyreFormError.BEFORE_LAST, c.addChange(LocalDate.parse("2026-09-30"), TyreSet.WINTER, ""))
        assertEquals(1, fake.savedChanges, "only the accepted fitting was written")
    }

    @Test
    fun `the newest fitting is removed and its sent reminders are forgotten`() = runTest {
        val fake = FakeTyreSettings()
        val c = controller(fake)
        assertNull(c.addChange(LocalDate.parse("2026-10-01"), TyreSet.SUMMER, ""))
        advanceUntilIdle()
        assertNull(c.addChange(LocalDate.parse("2026-11-01"), TyreSet.WINTER, ""))
        advanceUntilIdle()
        fake.keys = "WINTER:2026-11-01:months"

        assertTrue(c.removeLastChange())
        advanceUntilIdle()

        assertFalse(fake.changes!!.contains("2026-11-01"), "the newest fitting is gone")
        assertTrue(fake.changes!!.contains("2026-10-01"), "the older one stays")
        assertEquals(1, fake.savedKeys, "the reminders of the removed fitting are forgotten")
        assertFalse(fake.keys!!.contains("WINTER:2026-11-01"))
    }

    @Test
    fun `without any fitting there is nothing to remove`() = runTest {
        val fake = FakeTyreSettings()
        val c = controller(fake)
        assertFalse(c.removeLastChange())
        assertEquals(0, fake.savedChanges)
    }

    @Test
    fun `thresholds are saved as text and a wrong number refuses all of them`() = runTest {
        val fake = FakeTyreSettings()
        val c = controller(fake)

        assertNull(c.saveThresholds(mapOf(TyreSet.SUMMER to ("6" to "15000"), TyreSet.WINTER to ("" to ""))))
        advanceUntilIdle()
        assertTrue(fake.thresholds!!.contains("6"))
        assertEquals(6, c.state.value.thresholds[TyreSet.SUMMER]?.months)
        assertEquals(15_000, c.state.value.thresholds[TyreSet.SUMMER]?.km)
        assertNull(c.state.value.thresholds[TyreSet.WINTER]?.months)

        val before = fake.thresholds
        assertEquals(TyreFormError.THRESHOLD, c.saveThresholds(mapOf(TyreSet.SUMMER to ("0" to ""), TyreSet.WINTER to ("" to ""))))
        assertEquals(before, fake.thresholds, "a refused form writes nothing")
    }

    @Test
    fun `a failed read keeps the previous state and marks the failure`() = runTest {
        val fake = FakeTyreSettings()
        val c = controller(fake)
        c.load()
        advanceUntilIdle()
        val before = c.state.value

        fake.error = IllegalStateException("unreadable")
        c.load()
        advanceUntilIdle()

        assertTrue(c.state.value.failed)
        assertFalse(c.state.value.loading)
        assertEquals(before.changes, c.state.value.changes, "the fittings are phone settings, so they stay")
    }

    @Test
    fun `a logout drops the figures and a late read does not bring them back`() = runTest {
        val fake = FakeTyreSettings()
        val c = controller(fake)
        assertNull(c.addChange(LocalDate.parse("2026-10-01"), TyreSet.SUMMER, ""))
        advanceUntilIdle()
        assertFalse(c.state.value.changes.isEmpty())

        c.reset()
        advanceUntilIdle()

        assertEquals(TyreUiState(), c.state.value)
        assertTrue(fake.changes!!.isNotEmpty(), "the setting itself stays on the phone")
    }
}
