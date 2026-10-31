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
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.sync.RefuelChange

/**
 * What the refuel screens show (docs/SPEC.md §11.3). One flat state: the list and
 * the form of one entry are the same data seen from two places, and no part of it
 * is a secret (docs/SECURITY.md).
 *
 * @param loading the first read of the list is still running
 * @param refuels waiting refuels of the selected vehicle, newest first
 * @param loadFailed the local read failed — the text comes from [Texts], never the exception
 * @param busyId the entry a write is in flight for; both buttons stay blocked while it is set
 * @param formError why the last write or the last check failed, in Polish
 * @param formFieldErrors which field each message belongs to, so the form can mark it
 * @param savedId the entry whose write went through; the form returns to the list
 * when it recognises its own id
 */
data class RefuelUiState(
    val loading: Boolean = true,
    val refuels: List<Refuel> = emptyList(),
    val loadFailed: Boolean = false,
    val busyId: String? = null,
    val formError: String? = null,
    val formFieldErrors: List<RefuelFieldError> = emptyList(),
    val savedId: String? = null,
)

/**
 * State holder of the refuel list and the price form (docs/SPEC.md §11.3).
 *
 * Not a ViewModel and not scoped to a screen, following [SessionController]: the
 * app scope of `AppContainer` owns it, so a write that started in the form is not
 * cancelled by leaving the screen or by the composition going away. It only means
 * the *result* is applied where it still belongs — see [reset] and the generation
 * counter.
 *
 * Everything here runs on the UI scope ([scope]), so the state is written from one
 * thread and no lock is needed; [io] is where the local database is read and
 * written.
 *
 * @param pending reads the waiting refuels (`SyncEngine.pendingRefuels`).
 * @param confirmRefuel writes a price (`SyncEngine.confirmRefuel`), values already parsed.
 * @param dismissRefuel refuses an entry (`SyncEngine.dismissRefuel`).
 * @param onResolved an entry that stopped waiting: its notification is cancelled here, from
 *   whichever screen the write came (docs/SPEC.md §13.1).
 */
class RefuelController(
    private val pending: suspend () -> List<Refuel>,
    private val confirmRefuel: suspend (
        id: String,
        pricePerLiter: BigDecimal?,
        liters: BigDecimal?,
        totalCost: BigDecimal?,
        filledToFull: Boolean?,
        litersSource: LitersSource?,
    ) -> RefuelChange,
    private val dismissRefuel: suspend (id: String) -> RefuelChange,
    private val onResolved: (String) -> Unit = {},
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(RefuelUiState())
    val state: StateFlow<RefuelUiState> = ui.asStateFlow()

    /**
     * Bumped by [reset]. A result is applied only while the generation it started
     * in is still current, so a write that finished after a logout cannot bring
     * the list of the old account back (docs/SPEC.md §11.3).
     */
    private var generation = 0

    /** Reads the waiting refuels again: on entry to the list and after every write. */
    fun reload() {
        val startedIn = generation
        scope.launch {
            val read: Result<List<Refuel>> = try {
                Result.success(withContext(io) { pending() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = if (read.isSuccess) {
                ui.value.copy(loading = false, refuels = read.getOrThrow(), loadFailed = false)
            } else {
                ui.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    /**
     * Opens the form of [id]: the message and the "saved" signal of the previous
     * entry are gone. After a process death the form can be the first composable
     * with an empty state, so the list is read here too - otherwise the form would
     * wait forever for a read that nobody started (docs/SPEC.md §11.3).
     */
    fun openForm(id: String) {
        ui.value = ui.value.copy(formError = null, formFieldErrors = emptyList(), savedId = null)
        val shown = ui.value
        if (shown.loading || shown.loadFailed) reload()
    }

    /**
     * Confirms one entry with what the keyboard holds (docs/SPEC.md §11.3). The
     * values are parsed and checked before anything is written, so a bad form
     * never reaches the database; a second click while a write is in flight is
     * ignored. [litersFromEstimate] marks liters taken from the sensor estimate
     * (docs/SPEC.md §2a, §11.3).
     */
    fun confirm(
        id: String,
        priceText: String,
        litersText: String,
        totalText: String,
        filledToFull: Boolean?,
        litersFromEstimate: Boolean = false,
    ) {
        val shown = ui.value
        if (shown.busyId != null) return
        when (val check = checkRefuelForm(priceText, litersText, totalText, litersFromEstimate)) {
            is RefuelFormCheck.Invalid -> ui.value = shown.copy(
                formError = check.message,
                formFieldErrors = check.errors,
            )
            is RefuelFormCheck.Valid -> write(id) {
                // "Do pełna" without liters is what the domain rejects (docs/SPEC.md §2).
                confirmRefuel(
                    id,
                    check.price,
                    check.liters,
                    check.total,
                    check.liters?.let { filledToFull },
                    check.litersSource,
                )
            }
        }
    }

    /** Refuses an entry; the caller confirms the choice in a dialog first. */
    fun dismiss(id: String) {
        if (ui.value.busyId != null) return
        write(id) { dismissRefuel(id) }
    }

    /**
     * Called by [AppRoot] when the user is no longer logged in. The state is empty
     * again and every result of the old session is dropped — the write itself is
     * not cancelled, because the entry belongs to the archive of the account it
     * was read from (docs/SPEC.md §11.3).
     */
    fun reset() {
        generation++
        ui.value = RefuelUiState()
    }

    private fun write(id: String, op: suspend () -> RefuelChange) {
        val startedIn = generation
        ui.value = ui.value.copy(busyId = id, formError = null, formFieldErrors = emptyList(), savedId = null)
        scope.launch {
            val change: RefuelChange? = try {
                withContext(io) { op() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The database itself failed; its text is never shown (SECURITY.md).
                null
            }
            if (generation != startedIn) return@launch
            if (change == null) {
                ui.value = ui.value.copy(busyId = null, formError = Texts.REFUEL_SAVE_FAILED)
                return@launch
            }
            val saved = change is RefuelChange.Done
            // NoVehicle reloads the list too, but the entry still waits for its price, so
            // its notification stays (docs/SPEC.md §11.3, §13.1).
            val stale = when (change) {
                RefuelChange.NotFound, RefuelChange.NotPending -> true
                // NotConfirmed comes only from a correction of the log (§15.3), never from this form.
                is RefuelChange.Done, RefuelChange.Invalid, RefuelChange.NoVehicle, RefuelChange.NotConfirmed -> false
            }
            ui.value = ui.value.copy(
                busyId = null,
                formError = Texts.refuelChangeError(change),
                savedId = if (saved) id else null,
            )
            // Saved by us or answered elsewhere: in both cases the entry is no longer waiting.
            if (saved || stale) onResolved(id)
            if (saved || stale || change == RefuelChange.NoVehicle) reload()
        }
    }
}
