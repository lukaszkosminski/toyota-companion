package pl.lk.toyotacompanion.ui

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
import pl.lk.toyotacompanion.core.routes.TripRouteGateway
import pl.lk.toyotacompanion.core.routes.TripRouteRepository
import pl.lk.toyotacompanion.core.sync.NoCredentialsException
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException
import pl.lk.toyotacompanion.core.toyota.ToyotaLoginException
import pl.lk.toyotacompanion.core.toyota.ToyotaNetworkException

/** What the map knows about the route of the open trip (docs/SPEC.md §27); texts come from [Texts], never from exceptions. */
sealed interface RouteState {
    /** No trip route asked for (a parking place, or nothing open). */
    data object None : RouteState

    data object Loading : RouteState

    data class Ready(val points: List<TripRoutePoint>) : RouteState

    /** Toyota keeps no route for this trip (about 12 months of retention, or a trip without GPS). */
    data object Missing : RouteState

    data class Failed(val reason: RouteFailure) : RouteState
}

enum class RouteFailure { NETWORK, LOGIN, API }

/**
 * The GPS route of a trip for the map (docs/SPEC.md §27): from the local copy when there is one, otherwise one read-only
 * `GET /v1/trips/{id}` of Toyota, then saved. Only when a trip's map is opened - never during synchronization, so no
 * hundreds of requests and never `refresh_status()`. A failure asks nothing again until the map is opened again.
 */
class TripRouteController(
    private val readTrips: suspend () -> List<StoredTrip>,
    private val repository: TripRouteRepository,
    private val gateway: TripRouteGateway,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow<RouteState>(RouteState.None)
    val state: StateFlow<RouteState> = ui.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    /** Opens the route of [tripId]; `null` (a parking place) leaves the map with its points only. */
    fun open(tripId: String?) {
        job?.cancel()
        generation++
        if (tripId == null) {
            ui.value = RouteState.None
            return
        }
        val startedIn = generation
        ui.value = RouteState.Loading
        job = scope.launch {
            val result: RouteState = try {
                withContext(io) { load(tripId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoCredentialsException) {
                RouteState.Failed(RouteFailure.LOGIN)
            } catch (e: ToyotaLoginException) {
                RouteState.Failed(RouteFailure.LOGIN)
            } catch (e: ToyotaNetworkException) {
                RouteState.Failed(RouteFailure.NETWORK)
            } catch (e: Exception) {
                RouteState.Failed(RouteFailure.API)
            }
            if (generation == startedIn) ui.value = result
        }
    }

    /** Leaving the map or logging out: nothing keeps running and nothing of the route stays on screen. */
    fun close() {
        job?.cancel()
        job = null
        generation++
        ui.value = RouteState.None
    }

    private suspend fun load(tripId: String): RouteState {
        repository.get(tripId)?.let { return if (it.isEmpty()) RouteState.Missing else RouteState.Ready(it) }
        val vin = readTrips().firstOrNull { it.record.id == tripId }?.record?.vin ?: return RouteState.Failed(RouteFailure.API)
        val points = try {
            gateway.route(vin, tripId)
        } catch (e: ToyotaApiException) {
            // Toyota no longer has the trip: remember it, so the map does not ask again.
            if (e.statusCode == 404) {
                repository.save(tripId, emptyList())
                return RouteState.Missing
            }
            throw e
        }
        repository.save(tripId, points)
        return if (points.isEmpty()) RouteState.Missing else RouteState.Ready(points)
    }
}
