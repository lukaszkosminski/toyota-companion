package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
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
import pl.lk.toyotacompanion.core.domain.BestTimeGrid
import pl.lk.toyotacompanion.core.domain.bestTimeGrid

/** How many routes the screen shows. */
const val ROUTES_SHOWN: Int = 10

/** One route as the screen shows it: no position, only how it is driven. */
data class RouteSummary(
    val number: Int,
    val typicalKm: BigDecimal,
    val trips: Int,
    val usualHour: String?,
    val eco: BigDecimal?,
    val usualLPer100Km: BigDecimal?,
    /** Ids of the trips of the route, for its heat map (§34); an id is not a position. */
    val tripIds: List<String> = emptyList(),
)

/** A repeated route with the best time to leave (PRD F53, docs/SPEC.md §53): [number] is the route number on the screen. */
data class BestTimeRoute(val number: Int, val typicalKm: BigDecimal, val trips: Int, val grid: BestTimeGrid)

/** Everything "Styl jazdy" shows (docs/SPEC.md §19). Route numbers are the order of [routes], 1-based. */
data class DrivingStyleView(
    val months: List<MonthScore>,
    val routes: List<RouteSummary>,
    val anomalies: List<FuelAnomaly>,
    val tripCount: Int,
    /** The most driven routes (at most [BEST_TIME_ROUTES_SHOWN]) with at least [BEST_TIME_MIN_ROUTE_TRIPS] trips each (PRD F53). */
    val bestTimes: List<BestTimeRoute> = emptyList(),
)

data class DrivingStyleUiState(val loading: Boolean = true, val view: DrivingStyleView? = null, val loadFailed: Boolean = false)

/** The pure part: one pass over the trips of the selected vehicle in the zone of the trips list. */
fun computeDrivingStyle(trips: List<StoredTrip>, zone: ZoneId): DrivingStyleView {
    // Only the routes on screen: an anomaly must point at a route the user can see.
    val groups = sameRoutes(trips).take(ROUTES_SHOWN)
    return DrivingStyleView(
        months = ecoScoreByMonth(trips, zone),
        routes = groups.mapIndexed { index, group ->
            RouteSummary(
                number = index + 1,
                typicalKm = DrivingStyleFormatting.typicalKm(group),
                trips = group.trips.size,
                usualHour = DrivingStyleFormatting.usualHour(group, zone),
                eco = DrivingStyleFormatting.routeEco(group),
                usualLPer100Km = medianConsumption(group),
                tripIds = group.trips.map { it.record.id },
            )
        },
        // routeIndex is the index in `groups`, so route number = routeIndex + 1 on screen.
        anomalies = fuelAnomalies(groups),
        tripCount = trips.size,
        bestTimes = bestTimeRoutes(groups, zone),
    )
}

/**
 * State of "Styl jazdy" (PRD F12, F13), following [StatsController]: read in the navigation
 * callback, [ensureLoaded] after a process death, [reset] on logout.
 */
class DrivingStyleController(
    private val read: suspend () -> List<StoredTrip>,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(DrivingStyleUiState())
    val state: StateFlow<DrivingStyleUiState> = ui.asStateFlow()

    private var generation = 0

    /** The read in flight; a newer one cancels it, so an older result never overwrites a newer one (C22 D5). */
    private var loadJob: Job? = null
    private var loadStarted = false

    fun load() {
        loadStarted = true
        val startedIn = generation
        loadJob?.cancel()
        loadJob = scope.launch {
            val result: Result<DrivingStyleView> = try {
                Result.success(withContext(io) { computeDrivingStyle(read(), zone()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = if (result.isSuccess) {
                DrivingStyleUiState(loading = false, view = result.getOrThrow())
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
        ui.value = DrivingStyleUiState()
    }
}

/** A route needs this many trips for its best time to leave (PRD F53, docs/SPEC.md §53). */
const val BEST_TIME_MIN_ROUTE_TRIPS: Int = 5

/** At most this many of the most driven routes get a best time. */
const val BEST_TIME_ROUTES_SHOWN: Int = 5

/**
 * The best time to leave of the routes that have enough trips (PRD F53). [groups] are the routes on the screen, most driven first; a route with
 * fewer than [BEST_TIME_MIN_ROUTE_TRIPS] trips gets none. The grid is built from the trips of the route only.
 */
internal fun bestTimeRoutes(groups: List<RouteGroup>, zone: ZoneId): List<BestTimeRoute> = groups
    .mapIndexedNotNull { index, group ->
        if (group.trips.size < BEST_TIME_MIN_ROUTE_TRIPS) {
            null
        } else {
            BestTimeRoute(
                number = index + 1,
                typicalKm = DrivingStyleFormatting.typicalKm(group),
                trips = group.trips.size,
                grid = bestTimeGrid(group.trips.map { it.record.trip }, zone),
            )
        }
    }
    .take(BEST_TIME_ROUTES_SHOWN)
