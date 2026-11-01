package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
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
import pl.lk.toyotacompanion.core.routes.TripRoutePoint
import pl.lk.toyotacompanion.core.routes.TripRouteRepository

/** One drive on the heat map: its stored route, its consumption and where that sits between the route's best and worst. */
data class HeatLine(val points: List<TripRoutePoint>, val lPer100: BigDecimal, val share: Float)

/** What the heat map of one repeated route knows (docs/SPEC.md §34). */
sealed interface HeatState {
    data object None : HeatState

    data object Loading : HeatState

    /** No drive of the route has both a stored route and a consumption. */
    data class Empty(val total: Int) : HeatState

    /**
     * @param lines the drives with a stored route, oldest first
     * @param points start and end of the first drive, rounded to 0.001° like every point of a map
     * @param total drives of the route; [lines] is how many of them have a stored route
     */
    data class Ready(
        val lines: List<HeatLine>,
        val points: List<MapPoint>,
        val total: Int,
        val minLPer100: BigDecimal,
        val maxLPer100: BigDecimal,
    ) : HeatState

    data object Failed : HeatState
}

/** Share of [value] between [min] and [max], 0 (best) to 1 (worst); all equal gives 0.5. */
fun heatShare(value: BigDecimal, min: BigDecimal, max: BigDecimal): Float {
    val span = max.subtract(min)
    if (span.signum() <= 0) return 0.5f
    return value.subtract(min).divide(span, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
}

/** `[{"p":[[lat,lon],…],"f":0.25,"t":"4,3 l/100 km"},…]`: numbers and the consumption text only, the argument of the page's `showHeat`. */
fun heatJson(lines: List<HeatLine>): String = lines.joinToString(",", "[", "]") { line ->
    val pts = line.points.joinToString(",", "[", "]") { "[%.5f,%.5f]".format(java.util.Locale.ROOT, it.lat, it.lon) }
    "{\"p\":$pts,\"f\":%.3f,\"t\":\"%s\"}".format(java.util.Locale.ROOT, line.share, Texts.heatTooltip(TripsFormatting.plain(line.lPer100, 1)))
}

/**
 * The heat map of one repeated route (PRD F33, docs/SPEC.md §34): the drives of the route that have a stored route (§27, §33),
 * each coloured by its consumption. Reads the phone only - no request to Toyota; a drive without a stored route is counted, not
 * fetched. [reset] on logout or when the map is left.
 */
class RouteHeatController(
    private val readTrips: suspend () -> List<StoredTrip>,
    private val repository: TripRouteRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow<HeatState>(HeatState.None)
    val state: StateFlow<HeatState> = ui.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    fun open(tripIds: List<String>) {
        job?.cancel()
        generation++
        val startedIn = generation
        ui.value = HeatState.Loading
        job = scope.launch {
            val result: HeatState = try {
                withContext(io) { build(tripIds) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HeatState.Failed
            }
            if (generation == startedIn) ui.value = result
        }
    }

    fun close() {
        job?.cancel()
        job = null
        generation++
        ui.value = HeatState.None
    }

    private suspend fun build(tripIds: List<String>): HeatState {
        val wanted = tripIds.toSet()
        val trips = readTrips().filter { it.record.id in wanted }.sortedBy { it.record.trip.start }
        val drawn = ArrayList<Pair<StoredTrip, Pair<List<TripRoutePoint>, BigDecimal>>>()
        for (stored in trips) {
            val consumption = routeConsumption(stored) ?: continue
            val route = repository.get(stored.record.id)?.takeIf { it.size >= 2 } ?: continue
            drawn += stored to (route to consumption)
        }
        if (drawn.isEmpty()) return HeatState.Empty(trips.size)
        val values = drawn.map { it.second.second }
        val min = values.min()
        val max = values.max()
        return HeatState.Ready(
            lines = drawn.map { (_, data) -> HeatLine(data.first, data.second, heatShare(data.second, min, max)) },
            points = tripMapPoints(drawn.first().first.record),
            total = trips.size,
            minLPer100 = min,
            maxLPer100 = max,
        )
    }
}
