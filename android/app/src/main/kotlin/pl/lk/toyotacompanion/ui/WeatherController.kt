package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.LocalDate
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
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.WeatherRepository
import pl.lk.toyotacompanion.core.data.WeatherRow
import pl.lk.toyotacompanion.core.domain.WeatherImpact
import pl.lk.toyotacompanion.core.domain.weatherCostByPrecipitation
import pl.lk.toyotacompanion.core.domain.weatherCostByTemperature
import pl.lk.toyotacompanion.core.weather.WeatherDay
import pl.lk.toyotacompanion.core.weather.WeatherErrorKind
import pl.lk.toyotacompanion.core.weather.WeatherFetchException
import pl.lk.toyotacompanion.core.weather.WeatherGateway

/**
 * What the weather screen shows (PRD F15, docs/SPEC.md §23): the trips split by temperature and by
 * precipitation, and how much of the archive still waits for weather.
 *
 * @param plannedRequests requests a tap on "Pobierz pogodę" would make now, over all taps
 * @param requestsLeft of them, how many do not fit in one tap
 * @param cachedDays distinct days in the cache
 */
data class WeatherView(
    val temperature: WeatherImpact,
    val precipitation: WeatherImpact,
    val plannedRequests: Int,
    val requestsLeft: Int,
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
    private var fetchJob: Job? = null

    fun load() {
        loadStarted = true
        // A result of an earlier visit must not greet the next one.
        if (!ui.value.fetching) ui.value = ui.value.copy(lastFetch = null)
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
        fetchJob?.cancel()
        fetchJob = null
        ui.value = WeatherUiState()
    }

    /** Stops a running fetch (leaving the screen): nothing is requested in the background (docs/SPEC.md §23). */
    fun cancelFetch() {
        val job = fetchJob ?: return
        job.cancel()
        fetchJob = null
        ui.value = ui.value.copy(fetching = false)
    }

    /** Asks the service for the days the plan names, one place at a time, and recomputes the view. */
    fun fetch() {
        if (fetchJob?.isActive == true) return
        val startedIn = generation
        ui.value = ui.value.copy(fetching = true, lastFetch = null)
        fetchJob = scope.launch {
            val outcome: WeatherFetchResult = try {
                withContext(io) { fetchMissing() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything the steps below did not name; the text never carries the exception.
                WeatherFetchResult.DATA
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
        val stored = readTrips()
        val plan = planWeatherFetch(stored, cache.all(), zone(), today())
        if (plan.requests.isEmpty()) return WeatherFetchResult.NOTHING_TO_FETCH
        var received = 0
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
            received += days.size
            val now = clock()
            // A day the service left out is saved empty, or it would be asked for again at every tap.
            val answered = days.mapTo(HashSet()) { it.date }
            val last = minOf(request.end, today().minusDays(1))
            val absent = generateSequence(request.start) { it.plusDays(1) }
                .takeWhile { !it.isAfter(last) }
                .filter { it !in answered }
                .map { WeatherDay(it, null, null, null, null, null, null) }
                .toList()
            try {
                cache.save((days + absent).map { WeatherRow(request.place.latitude, request.place.longitude, it, now) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return WeatherFetchResult.STORAGE
            }
        }
        if (received == 0) return WeatherFetchResult.DATA
        // The cache holds no day older than the archive's first trip.
        stored.minOfOrNull { weatherDateOf(it, zone()) }?.let { first ->
            try {
                cache.pruneBefore(first.minusDays(1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pruning is housekeeping; a failure must not hide the days just fetched.
            }
        }
        return if (plan.requestsLeft > 0) WeatherFetchResult.PARTIAL else WeatherFetchResult.DONE
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
            plannedRequests = plan.requests.size + plan.requestsLeft,
            requestsLeft = plan.requestsLeft,
            cachedDays = rows.mapTo(HashSet()) { it.day.date }.size,
        )
    }

    /** The local date of the phone: the clock that dates the trips, so "yesterday" means the same for both. */
    private fun today(): LocalDate = LocalDate.ofInstant(clock(), zone())
}
