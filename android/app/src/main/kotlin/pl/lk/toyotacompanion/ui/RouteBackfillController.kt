package pl.lk.toyotacompanion.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.routes.TripRouteGateway
import pl.lk.toyotacompanion.core.routes.TripRouteRepository
import pl.lk.toyotacompanion.core.sync.NoCredentialsException
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException
import pl.lk.toyotacompanion.core.toyota.ToyotaLoginException
import pl.lk.toyotacompanion.core.toyota.ToyotaNetworkException

/** Most routes one tap fetches; the rest waits for the next tap (docs/SPEC.md §33). */
const val ROUTE_BACKFILL_PER_RUN = 100

/** Pause between two requests to Toyota, so a run does not look like an attack on the API. */
const val ROUTE_BACKFILL_PAUSE_MS = 300L

/** How a run ended; the text comes from [Texts], never from an exception. */
enum class BackfillResult { DONE, PARTIAL, NOTHING, NETWORK, LOGIN, API, STOPPED }

/**
 * @param total trips that have a position and so may have a route
 * @param stored of them, how many already have a route on the phone (an empty one counts: Toyota has none)
 * @param running a run is going
 * @param fetchedNow routes fetched in the current or last run
 */
data class BackfillState(
    val total: Int = 0,
    val stored: Int = 0,
    val running: Boolean = false,
    val fetchedNow: Int = 0,
    val result: BackfillResult? = null,
)

/**
 * Saves the routes of older trips before Toyota removes them (about 12 months, docs/SPEC.md §33): on a tap, oldest trips first,
 * up to [ROUTE_BACKFILL_PER_RUN] read-only `GET /v1/trips/{id}` per run, one at a time with a pause, never during a
 * synchronization. A trip Toyota no longer has (404) is stored as "no route" and not asked again; any other failure stops the run.
 * Trips without any position are left out.
 */
class RouteBackfillController(
    private val readTrips: suspend () -> List<StoredTrip>,
    private val repository: TripRouteRepository,
    private val gateway: TripRouteGateway,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val ui = MutableStateFlow(BackfillState())
    val state: StateFlow<BackfillState> = ui.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    private fun candidates(trips: List<StoredTrip>): List<StoredTrip> = trips
        .filter { it.record.startLat != null || it.record.endLat != null }
        .sortedBy { it.record.trip.start }

    /** Counts what is stored and what is missing; cheap, called when the trips list opens. */
    fun refresh() {
        if (job?.isActive == true) return
        val startedIn = generation
        scope.launch {
            val counts = try {
                withContext(io) {
                    val all = candidates(readTrips())
                    val stored = repository.storedIds()
                    all.size to all.count { it.record.id in stored }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (generation == startedIn && counts != null && job?.isActive != true) {
                ui.value = ui.value.copy(total = counts.first, stored = counts.second)
            }
        }
    }

    /** Starts a run on the oldest trips without a stored route. */
    fun start() {
        if (job?.isActive == true) return
        val startedIn = generation
        ui.value = ui.value.copy(running = true, fetchedNow = 0, result = null)
        job = scope.launch {
            var fetched = 0
            val result: BackfillResult = try {
                withContext(io) {
                    val all = candidates(readTrips())
                    val have = repository.storedIds().toMutableSet()
                    val todo = all.filter { it.record.id !in have }
                    if (todo.isEmpty()) return@withContext BackfillResult.NOTHING
                    for (stored in todo.take(ROUTE_BACKFILL_PER_RUN)) {
                        try {
                            val points = gateway.route(stored.record.vin, stored.record.id)
                            repository.save(stored.record.id, points)
                        } catch (e: ToyotaApiException) {
                            if (e.statusCode != 404) throw e
                            repository.save(stored.record.id, emptyList())
                        }
                        fetched++
                        if (generation == startedIn) {
                            ui.value = ui.value.copy(fetchedNow = fetched, stored = ui.value.stored + 1)
                        }
                        pause(ROUTE_BACKFILL_PAUSE_MS)
                    }
                    if (todo.size > ROUTE_BACKFILL_PER_RUN) BackfillResult.PARTIAL else BackfillResult.DONE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoCredentialsException) {
                BackfillResult.LOGIN
            } catch (e: ToyotaLoginException) {
                BackfillResult.LOGIN
            } catch (e: ToyotaNetworkException) {
                BackfillResult.NETWORK
            } catch (e: Exception) {
                BackfillResult.API
            }
            if (generation == startedIn) ui.value = ui.value.copy(running = false, result = result)
            if (generation == startedIn) refreshCounts()
        }
    }

    private suspend fun refreshCounts() {
        try {
            val counts = withContext(io) {
                val all = candidates(readTrips())
                val stored = repository.storedIds()
                all.size to all.count { it.record.id in stored }
            }
            ui.value = ui.value.copy(total = counts.first, stored = counts.second)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The counters are informative only.
        }
    }

    /** Stops a running run; what was fetched stays. */
    fun stop() {
        val running = job ?: return
        running.cancel()
        job = null
        ui.value = ui.value.copy(running = false, result = BackfillResult.STOPPED)
    }

    fun reset() {
        generation++
        job?.cancel()
        job = null
        ui.value = BackfillState()
    }
}
