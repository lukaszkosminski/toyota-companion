package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip

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

/** Everything "Styl jazdy" shows (docs/SPEC.md §19). Route numbers are the order of [routes], 1-based. */
data class DrivingStyleView(
    val months: List<MonthScore>,
    val routes: List<RouteSummary>,
    val anomalies: List<FuelAnomaly>,
    val tripCount: Int,
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
    private var loadStarted = false

    fun load() {
        loadStarted = true
        val startedIn = generation
        scope.launch {
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
        generation++
        loadStarted = false
        ui.value = DrivingStyleUiState()
    }
}
