package pl.lk.toyotacompanion.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.sync.RefuelLog

/**
 * What the refuel log shows (docs/SPEC.md §15.2).
 *
 * @param loading the first read is still running
 * @param log the refuels and intervals of the selected vehicle
 * @param loadFailed the read failed; the text comes from [Texts], never the exception
 */
data class RefuelLogUiState(
    val loading: Boolean = true,
    val log: RefuelLog = RefuelLog.EMPTY,
    val loadFailed: Boolean = false,
)

/**
 * State of the refuel log (PRD F9), following [RefuelController]: application
 * scope, a generation counter and [reset] on logout. [load] is called from the
 * navigation callback that opens the screen, not from an effect, so a rotation
 * does not read the database again (docs/SPEC.md §11.3); [ensureLoaded] covers a
 * screen restored after a process death.
 *
 * @param read the log of the selected vehicle (`SyncEngine.refuelLog`)
 */
class RefuelLogController(
    private val read: suspend () -> RefuelLog,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(RefuelLogUiState())
    val state: StateFlow<RefuelLogUiState> = ui.asStateFlow()

    private var generation = 0
    private var loadStarted = false

    /** Reads the log again; the navigation callback calls this every time the screen opens. */
    fun load() {
        loadStarted = true
        val startedIn = generation
        scope.launch {
            val result: Result<RefuelLog> = try {
                Result.success(withContext(io) { read() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = if (result.isSuccess) {
                RefuelLogUiState(loading = false, log = result.getOrThrow())
            } else {
                ui.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    /** A screen that is shown without a navigation callback (process death) reads once. */
    fun ensureLoaded() {
        if (!loadStarted) load()
    }

    /** Logout: the log of the old account is forgotten and late results are dropped. */
    fun reset() {
        generation++
        loadStarted = false
        ui.value = RefuelLogUiState()
    }
}
