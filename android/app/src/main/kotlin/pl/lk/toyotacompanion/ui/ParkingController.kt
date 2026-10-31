package pl.lk.toyotacompanion.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip

/**
 * @param loading the first read is still running
 * @param log the parking diary; `null` until read
 * @param loadFailed the read failed; the text comes from [Texts], never the exception
 */
data class ParkingUiState(val loading: Boolean = true, val log: ParkingLog? = null, val loadFailed: Boolean = false)

/**
 * State of "Postoje" (PRD F17, docs/SPEC.md §18): the diary is built from the archived trips
 * of the selected vehicle (`SyncEngine.trips`), never from a Toyota position request
 * (`refresh_status()` is forbidden, AGENTS.md). Read in the navigation callback; [ensureLoaded]
 * covers a process death; [reset] on logout.
 */
class ParkingController(
    private val read: suspend () -> List<StoredTrip>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(ParkingUiState())
    val state: StateFlow<ParkingUiState> = ui.asStateFlow()

    private var generation = 0
    private var loadStarted = false

    fun load() {
        loadStarted = true
        val startedIn = generation
        scope.launch {
            val result: Result<ParkingLog> = try {
                Result.success(withContext(io) { parkingLogOf(read()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = if (result.isSuccess) {
                ParkingUiState(loading = false, log = result.getOrThrow())
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
        ui.value = ParkingUiState()
    }
}
