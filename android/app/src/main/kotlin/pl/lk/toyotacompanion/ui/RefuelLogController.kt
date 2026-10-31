package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.sync.RefuelChange
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
    val busyId: String? = null,
    val amendError: String? = null,
    val amendedId: String? = null,
)

/**
 * State of the refuel log (PRD F9), following [RefuelController]: application
 * scope, a generation counter and [reset] on logout. [load] is called from the
 * navigation callback that opens the screen, not from an effect, so a rotation
 * does not read the database again (docs/SPEC.md §11.3); [ensureLoaded] covers a
 * screen restored after a process death.
 *
 * @param read the log of the selected vehicle (`SyncEngine.refuelLog`)
 * @param amend corrects a confirmed refuel with the receipt (`SyncEngine.amendRefuel`, §15.3)
 */
class RefuelLogController(
    private val read: suspend () -> RefuelLog,
    private val amend: suspend (id: String, liters: BigDecimal, totalCost: BigDecimal?, filledToFull: Boolean) -> RefuelChange,
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
                // A correction's result stays visible over the fresh read.
                ui.value.copy(loading = false, loadFailed = false, log = result.getOrThrow())
            } else {
                ui.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    /**
     * Corrects one confirmed refuel with the receipt (docs/SPEC.md §15.3). The liters are
     * required, the total is optional; a second click while a write runs is ignored. On
     * success the log is read again, so the economy card shows the correction at once.
     */
    fun amendLiters(id: String, litersText: String, totalText: String, filledToFull: Boolean) {
        val shown = ui.value
        if (shown.busyId != null) return
        val liters = (parseRefuelAmount(litersText) as? RefuelAmount.Given)?.value
        val total = when (val amount = parseRefuelAmount(totalText)) {
            RefuelAmount.Missing -> null
            is RefuelAmount.Given -> amount.value
            RefuelAmount.NotANumber -> {
                ui.value = shown.copy(amendError = Texts.REFUEL_NUMBER_ERROR, amendedId = null)
                return
            }
        }
        if (liters == null || liters <= BigDecimal.ZERO || (total != null && total <= BigDecimal.ZERO)) {
            ui.value = shown.copy(amendError = Texts.AMEND_LITERS_REQUIRED, amendedId = null)
            return
        }
        val startedIn = generation
        ui.value = shown.copy(busyId = id, amendError = null, amendedId = null)
        scope.launch {
            val change: RefuelChange? = try {
                withContext(io) { amend(id, liters, total, filledToFull) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The database failed; its text is never shown (docs/SECURITY.md).
                null
            }
            if (generation != startedIn) return@launch
            if (change is RefuelChange.Done) {
                ui.value = ui.value.copy(busyId = null, amendedId = id)
                load()
            } else {
                val message = change?.let { Texts.refuelChangeError(it) } ?: Texts.REFUEL_SAVE_FAILED
                ui.value = ui.value.copy(busyId = null, amendError = message)
            }
        }
    }

    /** The correction dialog was closed: its message is gone. */
    fun amendDismissed() {
        ui.value = ui.value.copy(amendError = null, amendedId = null)
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
