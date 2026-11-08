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
    val matrix: List<MatrixRow>,
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
    /** The weather of new trips is fetched on its own (docs/SPEC.md §23). */
    val auto: Boolean = true,
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
    private val autoEnabled: () -> Boolean = { true },
    private val saveAuto: (Boolean) -> Unit = {},
    private val clock: () -> Instant = { Instant.now() },
) {
    private val ui = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = ui.asStateFlow()

    private var generation = 0

    /** The read in flight; a newer one cancels it, so an older result never overwrites a newer one (C22 D5). */
    private var loadJob: Job? = null
    private var loadStarted = false
    private var fetchJob: Job? = null

    fun load() {
        loadStarted = true
        ui.value = ui.value.copy(auto = autoEnabled())
        // A result of an earlier visit must not greet the next one.
        if (!ui.value.fetching) ui.value = ui.value.copy(lastFetch = null)
        val startedIn = generation
        loadJob?.cancel()
        loadJob = scope.launch {
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
        loadJob?.cancel()
        loadJob = null
        generation++
        loadStarted = false
        fetchJob?.cancel()
        fetchJob = null
        ui.value = WeatherUiState()
    }

    /** The cached day of every trip that has one, for the trips list (docs/SPEC.md §10.3). */
    suspend fun daysOfTrips(): Map<String, WeatherDay> = withContext(io) { tripWeatherDays(readTrips(), cache.all(), zone()) }

    /** The switch of the screen: whether new trips get their weather on their own. */
    fun setAuto(on: Boolean) {
        saveAuto(on)
        ui.value = ui.value.copy(auto = on)
        if (on) autoFetch()
    }

    /**
     * Fetches the missing days on its own: after every successful synchronization and when the weather screen opens, in one
     * run of up to [WEATHER_MAX_REQUESTS_PER_AUTO_FETCH] requests, one at a time. Does nothing when switched off, when a run
     * is going, or when there is nothing to fetch; a failure stops the run and is shown as usual.
     */
    fun autoFetch() {
        if (!autoEnabled()) return
        startFetch(WEATHER_MAX_REQUESTS_PER_AUTO_FETCH, quietWhenNothing = true)
    }

    /** Stops a running fetch (leaving the screen): nothing is requested in the background (docs/SPEC.md §23). */
    fun cancelFetch() {
        // With the automatic fetch on, a run goes on after the screen is left: the owner asked for it to be automatic.
        if (autoEnabled()) return
        val job = fetchJob ?: return
        job.cancel()
        fetchJob = null
        ui.value = ui.value.copy(fetching = false)
    }

    /** Asks the service for the days the plan names, one place at a time, and recomputes the view. */
    fun fetch() = startFetch(WEATHER_MAX_REQUESTS_PER_FETCH, quietWhenNothing = false)

    private fun startFetch(maxRequests: Int, quietWhenNothing: Boolean) {
        if (fetchJob?.isActive == true) return
        val startedIn = generation
        ui.value = ui.value.copy(fetching = true, lastFetch = null)
        fetchJob = scope.launch {
            val outcome: WeatherFetchResult = try {
                withContext(io) { fetchMissing(maxRequests) }
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
                // An automatic run with nothing to do says nothing.
                lastFetch = if (quietWhenNothing && outcome == WeatherFetchResult.NOTHING_TO_FETCH) null else outcome,
                view = view ?: ui.value.view,
                loadFailed = view == null && ui.value.view == null,
            )
        }
    }

    private suspend fun fetchMissing(maxRequests: Int): WeatherFetchResult {
        val stored = readTrips()
        val plan = planWeatherFetch(stored, cache.all(), zone(), today(), maxRequests, clock())
        if (plan.requests.isEmpty() && plan.todayPlaces.isEmpty()) return WeatherFetchResult.NOTHING_TO_FETCH
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
                return e.kind.toFetchResult()
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
        for (place in plan.todayPlaces) {
            val days = try {
                gateway.forToday(place.latitude.toDouble(), place.longitude.toDouble())
            } catch (e: CancellationException) {
                throw e
            } catch (e: WeatherFetchException) {
                return e.kind.toFetchResult()
            }
            received += days.size
            try {
                cache.save(days.map { WeatherRow(place.latitude, place.longitude, it, clock()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return WeatherFetchResult.STORAGE
            }
        }
        if (received == 0 && plan.requests.isNotEmpty()) return WeatherFetchResult.DATA
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
        val plan = planWeatherFetch(stored, rows, zone(), today(), now = clock())
        return WeatherView(
            temperature = weatherCostByTemperature(trips, costs, weather),
            precipitation = weatherCostByPrecipitation(trips, costs, weather),
            plannedRequests = plan.requests.size + plan.requestsLeft + plan.todayPlaces.size,
            requestsLeft = plan.requestsLeft,
            cachedDays = rows.mapTo(HashSet()) { it.day.date }.size,
            matrix = speedWeatherMatrix(stored, weather),
        )
    }

    /**
     * The local date of the phone: the clock that dates the trips, so "yesterday" means the same for both.
     * `atZone().toLocalDate()` rather than `LocalDate.ofInstant`: the latter needs API 34 on Android and the app
     * runs on API 29+ (docs/SPEC.md §23).
     */
    private fun today(): LocalDate = clock().atZone(zone()).toLocalDate()
}

/** What the screen says about a failed request; one mapping for the archive and the today forecast (C22 D4). */
internal fun WeatherErrorKind.toFetchResult(): WeatherFetchResult = when (this) {
    WeatherErrorKind.NETWORK -> WeatherFetchResult.NETWORK
    WeatherErrorKind.RATE_LIMIT -> WeatherFetchResult.RATE_LIMIT
    WeatherErrorKind.API -> WeatherFetchResult.API
    WeatherErrorKind.DATA -> WeatherFetchResult.DATA
}
