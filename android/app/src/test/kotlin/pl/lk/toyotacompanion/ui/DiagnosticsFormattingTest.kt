package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.SyncStatus

/** PRD F46, docs/SPEC.md §47: the diagnostics report, without a VIN, a coordinate or a token. */
class DiagnosticsFormattingTest {

    private val zone = ZoneOffset.UTC
    private val at = Instant.parse("2026-11-09T12:34:00Z")

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
}
