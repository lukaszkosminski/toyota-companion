package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.domain.RefuelForecast

/** Beyond this the forecast says only "over 90 days" (docs/SPEC.md §20.2). */
const val FORECAST_HORIZON_DAYS: Long = 90

/** A forecast with the moment it was computed: its days count from then, not from now. */
data class TimedForecast(val forecast: RefuelForecast, val at: Instant)

/** Polish line of the forecast card (docs/SPEC.md §20.2); `null` hides the card. */
fun forecastLine(timed: TimedForecast?, zone: ZoneId): String? {
    timed ?: return null
    val forecast = timed.forecast
    val now = timed.at
    val left = Texts.forecastLeft(
        TripsFormatting.plain(forecast.litersLeft, 1) + " l",
        TripsFormatting.plain(forecast.levelPct, 0) + " %",
    )
    if (forecast.kmToReserve.signum() <= 0) return Texts.FORECAST_RESERVE + Texts.SEPARATOR + left
    val days = forecast.daysToReserve.min(java.math.BigDecimal(FORECAST_HORIZON_DAYS + 1))
        .setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    val whenText = if (days > FORECAST_HORIZON_DAYS) {
        Texts.forecastBeyond(FORECAST_HORIZON_DAYS)
    } else {
        val today = now.atZone(zone).toLocalDate()
        val day = today.plusDays(days)
        val date = day.dayOfMonth.toString() + "." + day.monthValue.toString().padStart(2, '0') +
            (if (day.year != today.year) "." + day.year else "")
        Texts.forecastWhen(days, date)
    }
    return whenText + Texts.SEPARATOR + left + Texts.SEPARATOR +
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
    private val clock: () -> Instant = { Instant.now() },
) {
    private val ui = MutableStateFlow<TimedForecast?>(null)
    val state: StateFlow<TimedForecast?> = ui.asStateFlow()

    private var generation = 0

    /** The read in flight; a newer one cancels it, so an older result never overwrites a newer one (C22 D5). */
    private var loadJob: Job? = null
    private var loadStarted = false

    fun refresh() {
        loadStarted = true
        val startedIn = generation
        loadJob?.cancel()
        loadJob = scope.launch {
            val forecast = try {
                withContext(io) { read()?.let { TimedForecast(it, clock()) } }
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
        loadJob?.cancel()
        loadJob = null
        generation++
        loadStarted = false
        ui.value = null
    }
}
