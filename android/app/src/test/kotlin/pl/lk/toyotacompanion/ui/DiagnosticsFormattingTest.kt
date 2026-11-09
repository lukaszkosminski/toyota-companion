package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.SyncStatus

/** PRD F46, docs/SPEC.md §47: the diagnostics report, without a VIN, a coordinate or a token. */
class DiagnosticsFormattingTest {

    private val zone = ZoneOffset.UTC
    private val at = Instant.parse("2026-11-09T12:34:00Z")
    private var previousLanguage = AppLanguage.PL

    // The texts are global state; a test that leaves EN behind would break the Polish assertions (D1).
    @Before
    fun usePolish() {
        previousLanguage = Lang.current
        Lang.current = AppLanguage.PL
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }

    private fun status(state: SyncState = SyncState()) = SyncStatus(
        state = state,
        tripsStored = 585,
        readingsStored = 120,
        pendingRefuels = 1,
        carStateAt = at.minusSeconds(3_600),
    )

    @Test
    fun `a timestamp is year month day hour minute or no value`() {
        assertEquals("2026-11-09 12:34", diagnosticsTimestamp(at, zone))
        assertEquals("2026-01-02 03:04", diagnosticsTimestamp(Instant.parse("2026-01-02T03:04:00Z"), zone))
        assertEquals(Texts.DIAGNOSTICS_NONE, diagnosticsTimestamp(null, zone))
    }

    @Test
    fun `the report shows every step, the car state time and the archive`() {
        val report = diagnosticsReport(
            status(
                SyncState(
                    lastAttemptAt = at,
                    lastSuccessAt = at.minusSeconds(60),
                    lastVehiclesSuccessAt = at.minusSeconds(120),
                    lastTelemetrySuccessAt = at.minusSeconds(180),
                    lastTripsSuccessAt = at.minusSeconds(240),
                ),
            ),
            appVersion = "0.2.5",
            zone = zone,
        )

        val lines = report.lines()
        assertTrue(lines.first() == "Toyota Companion 0.2.5")
        assertTrue(lines.contains("Ostatnia próba: 2026-11-09 12:34"))
        assertTrue(lines.contains("Ostatni pełny sukces: 2026-11-09 12:33"))
        assertTrue(lines.contains("Pojazdy i konto: 2026-11-09 12:32"))
        assertTrue(lines.contains("Telemetria: 2026-11-09 12:31"))
        assertTrue(lines.contains("Trasy: 2026-11-09 12:30"))
        assertTrue(lines.contains("Stan auta z Toyoty: 2026-11-09 11:34"))
        assertTrue(lines.contains("Ostatni błąd: brak"))
        assertTrue(lines.contains("Archiwum: trasy 585, odczyty 120, tankowania bez ceny 1"))
    }

    @Test
    fun `the last error carries its time, kind and HTTP status`() {
        val report = diagnosticsReport(
            status(
                SyncState(
                    lastErrorAt = at,
                    lastErrorKind = SyncErrorKind.API,
                    lastErrorMessage = "Toyota API request failed",
                    lastErrorStatus = 429,
                ),
            ),
            appVersion = "0.2.5",
            zone = zone,
        )

        assertTrue("Ostatni błąd: 2026-11-09 12:34 · API · HTTP 429" in report.lines())
        assertTrue("Komunikat: Toyota API request failed" in report.lines())
    }

    @Test
    fun `a failure without an HTTP status shows no code`() {
        val report = diagnosticsReport(
            status(SyncState(lastErrorAt = at, lastErrorKind = SyncErrorKind.NETWORK)),
            appVersion = "0.2.5",
            zone = zone,
        )

        assertTrue("Ostatni błąd: 2026-11-09 12:34 · NETWORK" in report.lines())
        assertTrue("HTTP" !in report)
    }

    @Test
    fun `the report has no vin, coordinate or token`() {
        val report = diagnosticsReport(
            status(
                SyncState(
                    vin = "VIN1234567890",
                    lastErrorAt = at,
                    lastErrorKind = SyncErrorKind.API,
                    lastErrorStatus = 403,
                    lastErrorMessage = "denied for vin=VIN1234567890 access_token=SECRET123",
                ),
            ),
            appVersion = "0.2.5",
            zone = zone,
        )

        for (forbidden in listOf("VIN1234567890", "SECRET123", "54.372", "18.638")) {
            assertTrue(forbidden !in report, "$forbidden leaked into:\n$report")
        }
        assertTrue("REDACTED" in report, report)
    }

    @Test
    fun `a bare vin in a message is masked to its last four characters`() {
        val report = diagnosticsReport(
            status(
                SyncState(
                    vin = "VIN1234567890",
                    lastErrorAt = at,
                    lastErrorKind = SyncErrorKind.API,
                    lastErrorMessage = "VIN1234567890 not found",
                ),
            ),
            appVersion = "0.2.5",
            zone = zone,
        )

        assertTrue("VIN1234567890" !in report, report)
        assertTrue("Komunikat: *********7890 not found" in report.lines(), report)
    }

    @Test
    fun `without a known vin the message stays as it is`() {
        val report = diagnosticsReport(
            status(SyncState(lastErrorAt = at, lastErrorKind = SyncErrorKind.API, lastErrorMessage = "some failure")),
            appVersion = "0.2.5",
            zone = zone,
        )

        assertTrue("Komunikat: some failure" in report.lines())
    }

    @Test
    fun `a message without an error kind is not shown`() {
        val report = diagnosticsReport(
            status(SyncState(lastErrorMessage = "stary komunikat")),
            appVersion = "0.2.5",
            zone = zone,
        )

        assertTrue("Ostatni błąd: brak" in report.lines())
        assertTrue("Komunikat" !in report, report)
    }

    @Test
    fun `the report follows the chosen language`() {
        Lang.current = AppLanguage.EN
        try {
            val report = diagnosticsReport(
                status(SyncState(lastAttemptAt = at, lastErrorAt = at, lastErrorKind = SyncErrorKind.NETWORK)),
                appVersion = "0.2.5",
                zone = zone,
            )

            assertTrue("Sync diagnostics" in report.lines(), report)
            assertTrue("Last attempt: 2026-11-09 12:34" in report.lines(), report)
            assertTrue("Last error: 2026-11-09 12:34 · NETWORK" in report.lines(), report)
            assertTrue("Archive: trips 585, readings 120, refuels without a price 1" in report.lines(), report)
        } finally {
            Lang.current = AppLanguage.PL
        }
    }
}
