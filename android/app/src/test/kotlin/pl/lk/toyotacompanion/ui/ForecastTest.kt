package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.RefuelForecast

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
private val NOW: Instant = Instant.parse("2026-10-31T12:00:00Z")

private fun forecast(km: String = "344.0", days: String = "17.2") = RefuelForecast(
    litersLeft = BigDecimal("21.500"),
    litersFree = BigDecimal("21.500"),
    levelPct = BigDecimal("50.0"),
    lPer100Km = BigDecimal("5.00"),
    dailyKm = BigDecimal("20.0"),
    kmToReserve = BigDecimal(km),
    daysToReserve = BigDecimal(days),
)

/** docs/SPEC.md §20.2: the forecast line of the status screen. */
class ForecastTest {

    private fun line(days: String, km: String = "344.0", at: Instant = NOW) =
        forecastLine(TimedForecast(forecast(km = km, days = days), at), WARSAW)

    @Test
    fun `the line names the day, the fuel left and the range`() {
        assertEquals(
            "Tankowanie za ok. 17 dni (ok. 17.11) \u00b7 w baku ok. 21,5 l (50 %) \u00b7 do pełna ok. 21,5 l \u00b7 ok. 344 km do rezerwy",
            line("17.2"),
        )
    }

    @Test
    fun `less than half a day is today and one day is tomorrow`() {
        assertEquals("Tankowanie dzi\u015b", line("0.4", km = "8.0")?.substringBefore(" \u00b7"))
        assertEquals("Tankowanie jutro (1.11)", line("1.2", km = "24.0")?.substringBefore(" \u00b7"))
    }

    @Test
    fun `beyond ninety days there is no date`() {
        assertEquals("Tankowanie za ponad 90 dni", line("400.0")?.substringBefore(" \u00b7"))
    }

    @Test
    fun `a date in the next year carries the year`() {
        assertEquals("Tankowanie za ok. 40 dni (ok. 9.01.2027)", line("40.0", at = Instant.parse("2026-11-30T12:00:00Z"))?.substringBefore(" \u00b7"))
    }

    @Test
    fun `the days count from the moment of the forecast`() {
        // Computed on 29.10: the date stays 15.11 however late the screen shows it.
        assertEquals("Tankowanie za ok. 17 dni (ok. 15.11)", line("17.2", at = Instant.parse("2026-10-29T12:00:00Z"))?.substringBefore(" \u00b7"))
    }

    @Test
    fun `at the reserve the line says refuel`() {
        assertEquals(Texts.FORECAST_RESERVE, line("0.0", km = "0.0")?.substringBefore(" \u00b7"))
    }

    @Test
    fun `no forecast hides the card`() {
        assertNull(forecastLine(null, WARSAW))
    }

    @Test
    fun `the controller reads once after login and drops a late result after logout`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var reads = 0
        val gate = CompletableDeferred<Unit>()
        val controller = ForecastController(
            read = {
                reads++
                gate.await()
                forecast()
            },
            scope = CoroutineScope(SupervisorJob() + io),
            io = io,
        )
        controller.ensureLoaded()
        controller.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, reads)

        controller.reset()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(controller.state.value)
    }

    @Test
    fun `a failing read hides the card`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val controller = ForecastController(
            read = { throw IllegalStateException("db") },
            scope = CoroutineScope(SupervisorJob() + io),
            io = io,
        )
        controller.refresh()
        advanceUntilIdle()
        assertNull(controller.state.value)
    }
}
