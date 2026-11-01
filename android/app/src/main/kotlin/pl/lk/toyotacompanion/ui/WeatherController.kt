package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.WeatherRepository
import pl.lk.toyotacompanion.core.data.WeatherRow
import pl.lk.toyotacompanion.core.domain.WeatherImpact
import pl.lk.toyotacompanion.core.domain.weatherCostByPrecipitation
import pl.lk.toyotacompanion.core.domain.weatherCostByTemperature
import pl.lk.toyotacompanion.core.weather.WeatherErrorKind
import pl.lk.toyotacompanion.core.weather.WeatherFetchException
import pl.lk.toyotacompanion.core.weather.WeatherGateway

/**
 * What the weather screen shows (PRD F15, docs/SPEC.md §23): the trips split by temperature and by
 * precipitation, and how much of the archive still waits for weather.
 *
 * @param plannedPlaces places (start points of about 1 km) that a tap on "Pobierz pogodę" would ask for now
 * @param placesLeft of them, how many do not fit in one tap
 * @param cachedDays days in the cache
 */
data class WeatherView(
    val temperature: WeatherImpact,
    val precipitation: WeatherImpact,
    val plannedPlaces: Int,
    val placesLeft: Int,
    val cachedDays: Int,
)

/** How the last tap on "Pobierz pogodę" ended; the text comes from [Texts], never from an exception. */
enum class WeatherFetchResult { DONE, PARTIAL, NOTHING_TO_FETCH, NETWORK, RATE_LIMIT, API, DATA, STORAGE }

/**
 * @param fetching a request is running, so the button waits
 * @param lastFetch outcome of the last tap, `null` before the first
 */
data class WeatherUiState(
    val loading: Boolean = true,
    val view: WeatherView? = null,
    val loadFailed: Boolean = false,
    val fetching: Boolean = false,
    val lastFetch: WeatherFetchResult? = null,
)

/**
 * Reads the trips and the weather cache, and fetches missing days only on an explicit tap - never on
 * synchronization (docs/SPEC.md §23). Requests run one after another: the free service answers one at a time.
 * Positions stay in the cache as rounded text; the domain only gets a map by trip id.
 */
class WeatherController(
    private val readTrips: suspend () -> List<StoredTrip>,
    private val cache: WeatherRepository,
    private val gateway: WeatherGateway,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val ui = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = ui.asStateFlow()

    private var generation = 0
    private var loadStarted = false

    fun load() {
        loadStarted = true
        val startedIn = generation
        scope.launch {
            val result: Result<WeatherView> = try {
                Result.success(withContext(io) { compute() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = if (result.isSuccess) {
                ui.value.copy(loading = false, view = result.getOrThrow(), loadFailed = false)
            } else {
                ui.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    fun ensureLoaded() {
        if (!loadStarted) load()
    }

    fun reset() {
        generation++
        loadStarted = false
        ui.value = WeatherUiState()
    }

    /** Asks the service for the days the plan names, one place at a time, and recomputes the view. */
    fun fetch() {
        if (ui.value.fetching) return
        val startedIn = generation
        ui.value = ui.value.copy(fetching = true, lastFetch = null)
        scope.launch {
            val outcome: WeatherFetchResult = try {
                withContext(io) { fetchMissing() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeatherFetchResult.STORAGE
            }
            if (generation != startedIn) return@launch
            val view = try {
                withContext(io) { compute() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            ui.value = ui.value.copy(
                fetching = false,
                lastFetch = outcome,
                view = view ?: ui.value.view,
                loadFailed = view == null && ui.value.view == null,
            )
        }
    }

    private suspend fun fetchMissing(): WeatherFetchResult {
        val plan = planWeatherFetch(readTrips(), cache.all(), zone(), today())
        if (plan.requests.isEmpty()) return WeatherFetchResult.NOTHING_TO_FETCH
        for (request in plan.requests) {
            val days = try {
                gateway.daily(
                    request.place.latitude.toDouble(),
                    request.place.longitude.toDouble(),
                    request.start,
                    request.end,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: WeatherFetchException) {
                return when (e.kind) {
                    WeatherErrorKind.NETWORK -> WeatherFetchResult.NETWORK
                    WeatherErrorKind.RATE_LIMIT -> WeatherFetchResult.RATE_LIMIT
                    WeatherErrorKind.API -> WeatherFetchResult.API
                    WeatherErrorKind.DATA -> WeatherFetchResult.DATA
                }
            }
            val now = clock()
            cache.save(days.map { WeatherRow(request.place.latitude, request.place.longitude, it, now) })
        }
        return if (plan.placesLeft > 0) WeatherFetchResult.PARTIAL else WeatherFetchResult.DONE
    }

    private suspend fun compute(): WeatherView {
        val stored = readTrips()
        val rows = cache.all()
        val trips = stored.map { it.record.trip }
        val costs = stored.mapNotNull { it.cost }
        val weather = tripWeather(stored, rows, zone())
        val plan = planWeatherFetch(stored, rows, zone(), today())
        return WeatherView(
            temperature = weatherCostByTemperature(trips, costs, weather),
            precipitation = weatherCostByPrecipitation(trips, costs, weather),
            plannedPlaces = plan.requests.size + plan.placesLeft,
            placesLeft = plan.placesLeft,
            cachedDays = rows.size,
        )
    }

    private fun today(): LocalDate = LocalDate.ofInstant(clock(), ZoneOffset.UTC)
}
