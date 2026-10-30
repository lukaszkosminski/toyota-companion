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
import pl.lk.toyotacompanion.core.sync.CostRecompute

/**
 * What the default price screen shows (docs/SPEC.md §12.3). A price is not a
 * secret, so the whole state may be kept in plain memory (docs/SECURITY.md).
 *
 * @param loading the first read of the setting is still running
 * @param price the stored price, `null` when none is set
 * @param loadFailed the settings read failed; the text comes from [Texts], never the exception
 * @param busy a write is in flight; both buttons stay blocked while it is set
 * @param error why the last check or write failed, in Polish
 * @param saved a write went through; the screen returns to the status when it sees it
 */
data class DefaultPriceUiState(
    val loading: Boolean = true,
    val price: BigDecimal? = null,
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

/**
 * State holder of the default price screen (docs/SPEC.md §12.3), following
 * [RefuelController]: it lives in the application scope of `AppContainer`, so
 * storing the price and recomputing the costs is not cancelled by leaving the
 * screen. [reset] and the generation counter only decide where a result may still
 * be applied.
 *
 * Logging out calls [reset], which forgets the state in memory but never the
 * setting itself: the price belongs to the phone, not to the account
 * (docs/SPEC.md §12.2, ADR-006).
 *
 * @param read reads the stored price (`SettingsStore.defaultPrice`)
 * @param persist writes the price, `null` removes the setting (`SettingsStore`)
 * @param recompute recounts the trip costs (`SyncEngine.recomputeCostsNow`)
 * @param scope the UI scope; the state is written from one thread, so no lock is needed
 * @param io where the settings file and the database are touched
 */
class DefaultPriceController(
    private val read: suspend () -> BigDecimal?,
    private val persist: suspend (BigDecimal?) -> Unit,
    private val recompute: suspend () -> CostRecompute,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(DefaultPriceUiState())
    val state: StateFlow<DefaultPriceUiState> = ui.asStateFlow()

    /**
     * Bumped by [reset]. A result is applied only while the generation it started
     * in is current, so a late write of the old account cannot fill the screen of
     * the next one (docs/SPEC.md §12.3).
     */
    private var generation = 0

    /**
     * Reads the stored price only when it is still unknown ([DefaultPriceUiState.loading]
     * or [DefaultPriceUiState.loadFailed]). `AppRoot` calls this from a `LaunchedEffect`
     * that restarts when the Activity is recreated, so without this guard every rotation
     * would read the settings file again (docs/SPEC.md §12.3).
     */
    fun ensureLoaded() {
        val shown = ui.value
        if (shown.loading || shown.loadFailed) load()
    }

    /** Reads the stored price again, e.g. after a failed read the user can retry. */
    fun load() {
        val startedIn = generation
        scope.launch {
            val readResult: Result<BigDecimal?> = try {
                Result.success(withContext(io) { read() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = if (readResult.isSuccess) {
                ui.value.copy(loading = false, loadFailed = false, price = readResult.getOrThrow())
            } else {
                ui.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    /**
     * Opens the screen: the message of an earlier attempt is gone. The setting is
     * read when it is still unknown, so a screen reached after a process death does
     * not wait for a read that nobody started.
     */
    fun open() {
        ui.value = ui.value.copy(error = null, saved = false)
        ensureLoaded()
    }

    /** Stores what the keyboard holds, after [parseDefaultPrice] accepted it (docs/SPEC.md §12.3). */
    fun save(text: String) {
        if (ui.value.busy) return
        when (val check = parseDefaultPrice(text)) {
            is DefaultPriceCheck.Invalid -> ui.value = ui.value.copy(error = check.message)
            is DefaultPriceCheck.Valid -> write(check.price)
        }
    }

    /** Removes the setting; the caller confirms the choice in a dialog first. */
    fun remove() {
        if (ui.value.busy) return
        write(null)
    }

    /**
     * Called by [AppRoot] when the user is no longer logged in. Only the state in
     * memory is forgotten — the setting stays on the phone (docs/SPEC.md §12.2) and
     * the write that is in flight is not cancelled, because it belongs to the
     * application scope.
     */
    fun reset() {
        generation++
        ui.value = DefaultPriceUiState()
    }

    private fun write(price: BigDecimal?) {
        val startedIn = generation
        ui.value = ui.value.copy(busy = true, error = null, saved = false)
        scope.launch {
            val stored: Boolean = try {
                withContext(io) { persist(price) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (generation != startedIn) return@launch
            if (!stored) {
                ui.value = ui.value.copy(busy = false, error = Texts.REFUEL_SAVE_FAILED)
                return@launch
            }
            // The recount is best effort. NoVehicle, or a recount that itself failed, does not
            // undo the setting: the next synchronization costs the trips with this price anyway
            // (docs/SPEC.md §12.3, decision "NoVehicle is a success").
            try {
                withContext(io) { recompute() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ignored for the reason above.
            }
            if (generation != startedIn) return@launch
            ui.value = ui.value.copy(
                loading = false,
                loadFailed = false,
                busy = false,
                price = price,
                error = null,
                saved = true,
            )
        }
    }
}
