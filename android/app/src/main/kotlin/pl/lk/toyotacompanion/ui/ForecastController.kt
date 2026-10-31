package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.domain.RefuelForecast

/** Polish line of the forecast card (docs/SPEC.md §20.2); `null` hides the card. */
fun forecastLine(forecast: RefuelForecast?, now: Instant, zone: ZoneId): String? {
    forecast ?: return null
    val left = Texts.forecastLeft(
        TripsFormatting.plain(forecast.litersLeft, 1) + " l",
        TripsFormatting.plain(forecast.levelPct, 0) + " %",
    )
    if (forecast.kmToReserve.signum() <= 0) return Texts.FORECAST_RESERVE + Texts.SEPARATOR + left
    val days = forecast.daysToReserve.setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    val day = now.atZone(zone).toLocalDate().plusDays(days)
    val date = day.dayOfMonth.toString() + "." + day.monthValue.toString().padStart(2, '0')
    return Texts.forecastWhen(days, date) + Texts.SEPARATOR + left + Texts.SEPARATOR +
        Texts.forecastRange(TripsFormatting.plain(forecast.kmToReserve, 0) + " km")
}

/**
 * The refuel forecast on the status screen (PRD F14, docs/SPEC.md §20.2): read once after
 * the login and again after every synchronization (`AppContainer.syncRunner`), never by
 * asking the car. A failed read hides the card; [reset] on logout.
 */
class ForecastController(
    private val read: suspend () -> RefuelForecast?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow<RefuelForecast?>(null)
    val state: StateFlow<RefuelForecast?> = ui.asStateFlow()

    private var generation = 0
    private var loadStarted = false

    fun refresh() {
        loadStarted = true
        val startedIn = generation
        scope.launch {
            val forecast = try {
                withContext(io) { read() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (generation == startedIn) ui.value = forecast
        }
    }

    fun ensureLoaded() {
        if (!loadStarted) refresh()
    }

    fun reset() {
        generation++
        loadStarted = false
        ui.value = null
    }
}
