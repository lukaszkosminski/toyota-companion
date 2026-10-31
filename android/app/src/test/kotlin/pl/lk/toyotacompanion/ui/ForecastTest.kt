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
    levelPct = BigDecimal("50.0"),
    lPer100Km = BigDecimal("5.00"),
    dailyKm = BigDecimal("20.0"),
    kmToReserve = BigDecimal(km),
    daysToReserve = BigDecimal(days),
)

/** docs/SPEC.md §20.2: the forecast line of the status screen. */
class ForecastTest {

    @Test
    fun `the line names the day, the fuel left and the range`() {
        assertEquals(
            listOf(Texts.forecastWhen(17, "17.11"), Texts.forecastLeft("21,5 l", "50 %"), Texts.forecastRange("344 km"))
                .joinToString(Texts.SEPARATOR),
            forecastLine(forecast(), NOW, WARSAW),
        )
    }

    @Test
    fun `less than half a day is today`() {
        val line = requireNotNull(forecastLine(forecast(km = "8.0", days = "0.4"), NOW, WARSAW))
        assertEquals(Texts.forecastWhen(0, "31.10"), line.substringBefore(Texts.SEPARATOR))
    }

    @Test
    fun `at the reserve the line says refuel`() {
        val line = requireNotNull(forecastLine(forecast(km = "0.0", days = "0.0"), NOW, WARSAW))
        assertEquals(Texts.FORECAST_RESERVE, line.substringBefore(Texts.SEPARATOR))
    }

    @Test
    fun `no forecast hides the card`() {
        assertNull(forecastLine(null, NOW, WARSAW))
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
