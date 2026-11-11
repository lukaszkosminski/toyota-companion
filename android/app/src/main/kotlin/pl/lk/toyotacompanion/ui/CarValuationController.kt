package pl.lk.toyotacompanion.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.settings.CarValuation

/**
 * What the car valuation card in "Opcje" shows (PRD F55, docs/SPEC.md §55). The purchase price and the estimated value
 * are not secrets and belong to the phone, so the whole state may be kept in plain memory (docs/SECURITY.md). No network:
 * the owner types both numbers.
 *
 * @param loading the first read of the setting is still running
 * @param valuation the stored valuation, `null` when none is set
 * @param loadFailed the settings read failed; the text comes from [Texts], never the exception
 * @param busy a write is in flight; the buttons stay blocked while it is set
 * @param error why the last write failed, in the language of the app
 */
data class CarValuationUiState(
    val loading: Boolean = true,
    val valuation: CarValuation? = null,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * State holder of the car valuation (PRD F55, docs/SPEC.md §55), following [DefaultPriceController]: it lives in the
 * application scope of `AppContainer`, so a write is not cancelled by leaving the screen. [reset] forgets only the state in
 * memory — the setting belongs to the phone and stays (ADR-006).
 */
class CarValuationController(
    private val read: suspend () -> CarValuation?,
    private val persist: suspend (CarValuation?) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(CarValuationUiState())
    val state: StateFlow<CarValuationUiState> = ui.asStateFlow()

    /** Bumped by [reset]; a late result of the old account is not shown to the next one. */
    private var generation = 0

    /** Reads the stored valuation only when it is still unknown, so a rotation does not touch the settings file again. */
    fun ensureLoaded() {
        val shown = ui.value
        if (shown.loading || shown.loadFailed) load()
    }

    /** Reads the stored valuation again, e.g. to retry after a failed read. */
    fun load() {
        val startedIn = generation
        scope.launch {
            val result: Result<CarValuation?> = try {
                Result.success(withContext(io) { read() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = if (result.isSuccess) {
                ui.value.copy(loading = false, loadFailed = false, valuation = result.getOrThrow())
            } else {
                ui.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    /** Opens the card: an earlier message is gone and the setting is read when it is still unknown. */
    fun open() {
        ui.value = ui.value.copy(error = null)
        ensureLoaded()
    }

    /** Stores a valuation already accepted by [parseCarValuation]. */
    fun save(valuation: CarValuation) {
        if (ui.value.busy) return
        write(valuation)
    }

    /** Removes the setting; the card shows the empty state afterwards. */
    fun remove() {
        if (ui.value.busy) return
        write(null)
    }

    /** Logging out forgets the state in memory only; the setting stays on the phone (ADR-006). */
    fun reset() {
        generation++
        ui.value = CarValuationUiState()
    }

    private fun write(valuation: CarValuation?) {
        val startedIn = generation
        ui.value = ui.value.copy(busy = true, error = null)
        scope.launch {
            val stored: Boolean = try {
                withContext(io) { persist(valuation) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (generation != startedIn) return@launch
            if (!stored) {
                ui.value = ui.value.copy(busy = false, error = Texts.CAR_VALUE_SAVE_FAILED)
                return@launch
            }
            ui.value = ui.value.copy(
                loading = false,
                loadFailed = false,
                busy = false,
                valuation = valuation,
                error = null,
            )
        }
    }
}
