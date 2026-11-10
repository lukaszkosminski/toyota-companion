package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.domain.ToyotaNotification

/** The second round of C32 (U18 notifications, U20 diagnostics): the masked VIN prefix, the names, the short stamps and the rows. */
class NotificationAndDiagnosticsTest {
    private val zone = ZoneOffset.UTC
    private var previousLanguage = AppLanguage.PL

    @Before
    fun usePolish() {
        previousLanguage = Lang.current
        Lang.current = AppLanguage.PL
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }

    // ---- U18(a): the masked VIN prefix ----

    @Test
    fun `the masked vin and colon in front of a message are cut`() {
        assertEquals("Klimatyzacja została uruchomiona.", notificationMessageText("*************4408: Klimatyzacja została uruchomiona."))
        assertEquals("Ładowanie przerwane [4].", notificationMessageText("****AB12: Ładowanie przerwane [4]."))
    }

    @Test
    fun `a message without the prefix stays as it is`() {
        assertEquals("Nowe powiadomienie", notificationMessageText("Nowe powiadomienie"))
        // Another shape is not the mask: no colon after the stars, no space, or the stars are not a mask.
        assertEquals("*************4408 Klimatyzacja", notificationMessageText("*************4408 Klimatyzacja"))
        assertEquals("**4408: Klimatyzacja", notificationMessageText("**4408: Klimatyzacja"))
        assertEquals("*************4408:Klimatyzacja", notificationMessageText("*************4408:Klimatyzacja"))
    }

    @Test
    fun `only the prefix is cut, stars and a VIN further inside stay`() {
        assertEquals("Serwis dla *********7890", notificationMessageText("*************4408: Serwis dla *********7890"))
        assertEquals("Zmiana dla VIN1234567890 w treści", notificationMessageText("Zmiana dla VIN1234567890 w treści"))
    }

    @Test
    fun `a message that is only the prefix leaves nothing and a missing one is null`() {
        assertNull(notificationMessageText("*************4408: "))
        assertNull(notificationMessageText("*************4408:  "))
        assertNull(notificationMessageText(""))
        assertNull(notificationMessageText(null))
    }

    // ---- U18(b): names of the values the reference documents ----

    @Test
    fun `the known categories and the alert type read in Polish and keep English`() {
        assertEquals("Ogólne", notificationCategoryText("General"))
        assertEquals("Alerty pojazdu", notificationCategoryText("Vehicle Alerts"))
        assertEquals("Zdalne polecenia", notificationCategoryText("Remote Commands"))
        assertEquals("ostrzeżenie", notificationTypeText("alert"))
        Lang.current = AppLanguage.EN
        assertEquals("General", notificationCategoryText("General"))
        assertEquals("Vehicle Alerts", notificationCategoryText("Vehicle Alerts"))
        assertEquals("alert", notificationTypeText("alert"))
    }

    @Test
    fun `an unknown category or type stays exactly as Toyota sent it`() {
        assertEquals("ChargingAlert", notificationCategoryText("ChargingAlert"))
        assertEquals("Service", notificationCategoryText("Service"))
        assertEquals("INFO", notificationTypeText("INFO"))
        assertEquals("Service · INFO", notificationKindText(ToyotaNotification(category = "Service", type = "INFO")))
        assertEquals("Ogólne · ostrzeżenie", notificationKindText(ToyotaNotification(category = "General", type = "alert")))
    }

    // ---- U20: the short stamp ----

    @Test
    fun `the short stamp has no year on this year and the year on another one`() {
        val at = Instant.parse("2026-11-10T19:04:00Z")
        assertEquals("10.11 19:04", TripsFormatting.shortStamp(at, zone, LocalDate.parse("2026-12-01")))
        assertEquals("10.11.2026 19:04", TripsFormatting.shortStamp(at, zone, LocalDate.parse("2027-02-01")))
        assertEquals("01.01.2026 00:00", TripsFormatting.shortStamp(Instant.parse("2026-01-01T00:00:00Z"), zone, LocalDate.parse("2027-01-01")))
    }

    @Test
    fun `the stamp follows the zone of the phone`() {
        // 23:30 UTC on 9 November is 00:30 on 10 November in Warsaw: the day of the screen is the local one.
        val warsaw = java.time.ZoneId.of("Europe/Warsaw")
        assertEquals("10.11 00:30", TripsFormatting.shortStamp(Instant.parse("2026-11-09T23:30:00Z"), warsaw, LocalDate.parse("2026-11-10")))
    }

    // ---- U20: the rows of the screen ----

    private val at = Instant.parse("2026-11-10T19:04:00Z")

    private fun status(state: SyncState) = SyncStatus(state = state, tripsStored = 1234, readingsStored = 120, pendingRefuels = 1)

    @Test
    fun `the diagnostics rows are label and value, short stamps and no title`() {
        val rows = diagnosticsRows(
            status(SyncState(lastAttemptAt = at, lastSuccessAt = at.minusSeconds(60))),
            "0.2.6",
            zone,
            LocalDate.parse("2026-11-10"),
        )
        assertEquals(LabelValue("Wersja aplikacji", "0.2.6"), rows.first())
        assertEquals(LabelValue("Ostatnia próba", "10.11 19:04"), rows[1])
        assertEquals(LabelValue("Ostatni pełny sukces", "10.11 19:03"), rows[2])
        assertEquals(LabelValue("Ostatni błąd", "brak"), rows.first { it.label == "Ostatni błąd" })
        assertFalse(rows.any { it.value.contains("2026-11-10") || it.value.contains("Diagnostyka") })
        assertEquals(LabelValue("Trasy w archiwum", "1\u00A0234"), rows.first { it.label == "Trasy w archiwum" })
    }

    @Test
    fun `an error is one row with its time, kind and status, and its message is masked`() {
        val rows = diagnosticsRows(
            status(
                SyncState(
                    vin = "VIN1",
                    lastErrorAt = at,
                    lastErrorKind = SyncErrorKind.NETWORK,
                    lastErrorStatus = 503,
                    lastErrorMessage = "Błąd dla VIN1 przy odczycie",
                ),
            ),
            "0.2.6",
            zone,
            LocalDate.parse("2026-11-10"),
        )
        assertEquals(LabelValue("Ostatni błąd", "10.11 19:04 · NETWORK · HTTP 503"), rows.first { it.label == "Ostatni błąd" })
        val message = rows.first { it.label == "Komunikat" }.value
        assertFalse("VIN1" in message, message)
        assertTrue(message.startsWith("Błąd dla"), message)
    }

    @Test
    fun `the screen rows carry no vin, coordinate or token`() {
        val rows = diagnosticsRows(status(SyncState(vin = "VINAAA111", lastErrorKind = SyncErrorKind.API, lastErrorMessage = "access_token=abc")), "0.2.6", zone, LocalDate.parse("2026-11-10"))
        val text = rows.joinToString("\n") { it.label + " " + it.value }
        assertFalse("VINAAA111" in text)
        assertFalse("abc" in text)
    }

    @Test
    fun `the copied report stays the same plain text`() {
        val report = diagnosticsReport(status(SyncState(lastAttemptAt = at)), "0.2.6", zone)
        assertTrue(report.lines().first() == "Toyota Companion 0.2.6")
        assertTrue(report.contains("2026-11-10 19:04"))
    }
}
