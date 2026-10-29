package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.SyncStatus

private val ZONE = ZoneOffset.UTC

private fun status(fresh: Boolean) = SyncStatus(
    state = if (fresh) {
        SyncState(vin = "VIN1", lastSuccessAt = Instant.parse("2026-10-29T10:00:00Z"))
    } else {
        SyncState(
            vin = "VIN1",
            lastErrorAt = Instant.parse("2026-10-29T11:00:00Z"),
            lastErrorKind = SyncErrorKind.NETWORK,
        )
    },
    tripsStored = 3,
    readingsStored = 2,
    pendingRefuels = 1,
)

/** Every error has a Polish message; no message leaks a secret (docs/SPEC.md §9, docs/SECURITY.md). */
class TextsTest {
    @Test
    fun `every login error has a message`() {
        for (error in LoginError.entries) {
            val text = Texts.loginError(error)
            assertTrue(text.isNotBlank(), error.name)
            assertTrue(text.first().isUpperCase(), "${error.name}: $text")
        }
    }

    @Test
    fun `rejected login does not blame the password alone`() {
        // Toyota also answers 5xx as a login failure, see docs/SPEC.md §9.1.
        assertTrue("odrzuciła" in Texts.loginError(LoginError.INVALID_CREDENTIALS))
    }

    @Test
    fun `every sync error kind has a message`() {
        for (kind in SyncErrorKind.entries) {
            assertTrue(Texts.syncError(kind).isNotBlank(), kind.name)
        }
    }

    @Test
    fun `freshness is current or not current`() {
        assertEquals("aktualne", Texts.freshness(status(true)))
        assertEquals("nieaktualne", Texts.freshness(status(false)))
    }

    @Test
    fun `last success is a timestamp or missing`() {
        assertEquals("Ostatnia synchronizacja: 2026-10-29 10:00", Texts.lastSuccess(status(true), ZONE))
        assertEquals("Ostatnia synchronizacja: brak", Texts.lastSuccess(status(false), ZONE))
    }

    @Test
    fun `messages carry no secret`() {
        val all = LoginError.entries.map { Texts.loginError(it) } +
            SyncErrorKind.entries.map { Texts.syncError(it) } +
            listOf(Texts.trips(1), Texts.readings(1), Texts.pendingRefuels(1), Texts.freshness(status(true))) +
            listOf(Texts.lastSuccess(status(true), ZONE), Texts.lastSuccess(status(false), ZONE))
        for (text in all) {
            for (forbidden in listOf("token", "Token", "VIN1", "p@ss", "secret")) {
                assertFalse(forbidden in text, "$forbidden in: $text")
            }
        }
    }
}
