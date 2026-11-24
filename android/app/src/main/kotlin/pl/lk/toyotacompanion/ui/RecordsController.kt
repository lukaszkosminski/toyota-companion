package pl.lk.toyotacompanion.ui

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
import pl.lk.toyotacompanion.core.domain.Records
import pl.lk.toyotacompanion.core.domain.computeRecords

/** The records screen state (PRD F62, docs/SPEC.md §62): loading, the records, or a read failure. */
data class RecordsUiState(val loading: Boolean = true, val records: Records? = null, val loadFailed: Boolean = false)

/**
 * Builds the personal records from the trips of the selected vehicle (`SyncEngine.trips`), in the
 * zone of the trips list. Read in the navigation callback, like the statistics screen, so a
 * rotation does not read again; [ensureLoaded] covers a process death.
 */
class RecordsController(
    private val read: suspend () -> List<StoredTrip>,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(RecordsUiState())
    val state: StateFlow<RecordsUiState> = ui.asStateFlow()

    private var generation = 0
    private var loadJob: Job? = null
    private var loadStarted = false

    fun load() {
        loadStarted = true
        val startedIn = generation
        loadJob?.cancel()
        loadJob = scope.launch {
            val result: Result<Records> = try {
                Result.success(withContext(io) { computeRecords(read().map { it.record.trip }, zone()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = if (result.isSuccess) {
                RecordsUiState(loading = false, records = result.getOrThrow())
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
        ui.value = RecordsUiState()
    }
}
