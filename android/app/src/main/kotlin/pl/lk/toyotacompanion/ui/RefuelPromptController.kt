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
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.sync.RefuelChange

/**
 * What the "ask about the price now" prompt shows (docs/SPEC.md §13.3).
 *
 * @param loading the waiting refuels have not been read yet
 * @param entry the refuel to ask about, null when nothing should be shown
 * @param priceText what the field of the dialog holds; kept here so a rotation
 *   does not lose an answer in progress
 * @param error why the last check or write failed, in Polish, from [Texts]
 * @param busy a write started from this dialog is in flight
 * @param formRequest an entry whose *full* form (liters and total cost) the user
 *   asked for, by "Więcej" or by tapping a notification; [AppRoot] navigates and
 *   then calls [formRequestHandled]
 */
data class RefuelPromptUiState(
    val loading: Boolean = true,
    val entry: Refuel? = null,
    val priceText: String = "",
    val error: String? = null,
    val busy: Boolean = false,
    val formRequest: Refuel? = null,
)

/**
 * State of the in-app question that follows a detected refuel
 * (docs/SPEC.md §13.3). It does not touch [RefuelController]: `busyId` and
 * `formError` belong to the full form, and the prompt must not disturb a form the
 * user may already have open.
 *
 * @param pending reads the refuels waiting for a price (`SyncEngine.pendingRefuels`).
 * @param confirmRefuel writes the price (`SyncEngine.confirmRefuel`), already parsed.
 * @param onResolved cancels the notification of an entry that is no longer waiting.
 * @param onSaved refreshes the counters after a write (`SessionController.reload`).
 * @param onFormScreen whether a form or the refuel list is on screen; [AppRoot]
 *   keeps it current, see docs/SPEC.md §13.3.
 */
class RefuelPromptController(
    private val pending: suspend () -> List<Refuel>,
    private val confirmRefuel: suspend (id: String, pricePerLiter: BigDecimal) -> RefuelChange,
    private val onResolved: (String) -> Unit = {},
    private val onSaved: () -> Unit = {},
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(RefuelPromptUiState())
    val state: StateFlow<RefuelPromptUiState> = ui.asStateFlow()

    /** Bumped by [reset]; results of the old session are dropped (docs/SPEC.md §11.3). */
    private var generation = 0

    /**
     * Entries the user put off with "Później". Memory only, for the life of the
     * process: a new refuel found later still asks, and logging in again asks
     * about these too (docs/SPEC.md §13.3).
     */
    private val deferred = mutableSetOf<String>()

    /** Last read of the waiting refuels, newest first. */
    private var waiting: List<Refuel> = emptyList()

    /** Id whose full form was requested before the first read finished. */
    private var pendingFormId: String? = null

    /** Kept current by [AppRoot]; a form on screen means "do not interrupt" (§13.3). */
    var onFormScreen: () -> Boolean = { false }

    /**
     * Reads the waiting refuels once — from `LaunchedEffect(loggedIn)`, which also
     * restarts on an Activity recreate. The guard is what keeps a rotation from
     * reading the database again (docs/SPEC.md §13.3).
     */
    fun ensureLoaded() {
        if (ui.value.loading) refresh()
    }

    /**
     * Reads the waiting refuels again. The decorated `SyncRunner` calls this after
     * every successful run that detected a refuel, so a background synchronization
     * that finds one while the app is open shows the prompt (docs/SPEC.md §13.3).
     */
    fun refresh() {
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
            // A failed read keeps whatever is on screen; the prompt never shows
            // database text (docs/SECURITY.md).
            if (read.isSuccess) applyRead(read.getOrThrow())
        }
    }

    /**
     * The screen changed — also after a rotation, whose effect restarts. Decides again
     * from the last read without touching the database (docs/SPEC.md §13.3): entries
     * priced or refused on the other screens leave that read through
     * [resolvedElsewhere], and a synchronization that found something calls [refresh].
     */
    fun screenChanged() {
        if (ui.value.loading) return
        applyRead(waiting)
    }

    /**
     * The full form or the list priced or refused [id]: it is not waiting any more,
     * so it leaves the last read and, unless our own write is running, the dialog.
     */
    fun resolvedElsewhere(id: String) {
        waiting = waiting.filterNot { it.id == id }
        val shown = ui.value
        if (shown.entry?.id == id && !shown.busy) ui.value = shown.copy(entry = null, priceText = "", error = null)
    }

    /** The keyboard holds [text]; an error message disappears with the next keystroke. */
    fun priceChanged(text: String) {
        ui.value = ui.value.copy(priceText = text, error = null)
    }

    /** Saves the price with the rules of §11.2 and moves on to the next entry. */
    fun save() {
        val shown = ui.value
        val entry = shown.entry ?: return
        if (shown.busy) return
        when (val check = checkRefuelForm(shown.priceText, "", "")) {
            is RefuelFormCheck.Invalid -> ui.value = shown.copy(error = check.message)
            is RefuelFormCheck.Valid -> write(entry, requireNotNull(check.price))
        }
    }

    /** "Później": this one entry is not asked about again in this process (§13.3). */
    fun later() {
        val entry = ui.value.entry ?: return
        deferred.add(entry.id)
        ui.value = ui.value.copy(entry = null, priceText = "", error = null)
    }

    /** "Więcej": the full form of C3, with liters and total cost. */
    fun more() {
        val entry = ui.value.entry ?: return
        ui.value = ui.value.copy(formRequest = entry)
    }

    /**
     * The user tapped the notification of [id]. Unknown id (already priced, or
     * refused elsewhere) means no action: the status screen stays as it is
     * (docs/SPEC.md §13.1).
     */
    fun openFromNotification(id: String) {
        waiting.firstOrNull { it.id == id }?.let { ui.value = ui.value.copy(formRequest = it) }
            ?: run {
                pendingFormId = id
                refresh()
            }
    }

    /** [AppRoot] navigated to [RefuelPromptUiState.formRequest]; the request is spent. */
    fun formRequestHandled() {
        ui.value = ui.value.copy(formRequest = null)
    }

    /** Logs out: the state is empty, "Później" is forgotten, late results are dropped. */
    fun reset() {
        generation++
        deferred.clear()
        waiting = emptyList()
        pendingFormId = null
        ui.value = RefuelPromptUiState()
    }

    private fun write(entry: Refuel, price: BigDecimal) {
        val startedIn = generation
        ui.value = ui.value.copy(busy = true, error = null)
        scope.launch {
            val change: RefuelChange? = try {
                withContext(io) { confirmRefuel(entry.id, price) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (generation != startedIn) return@launch
            when (change) {
                is RefuelChange.Done -> {
                    onResolved(entry.id)
                    onSaved()
                    afterWrite()
                }
                // The entry is gone: no message, just the next one (docs/SPEC.md §13.3).
                RefuelChange.NotFound, RefuelChange.NotPending -> {
                    deferred.add(entry.id)
                    onResolved(entry.id)
                    afterWrite()
                }
                RefuelChange.NoVehicle -> {
                    deferred.add(entry.id)
                    afterWrite()
                }
                // NotConfirmed comes only from a correction of the log (§15.3), never from the prompt.
                RefuelChange.Invalid, RefuelChange.NotConfirmed ->
                    ui.value = ui.value.copy(busy = false, error = Texts.refuelChangeError(change))
                null -> ui.value = ui.value.copy(busy = false, error = Texts.REFUEL_SAVE_FAILED)
            }
        }
    }

    /** After a write the dialog shows the next waiting entry or disappears. */
    private fun afterWrite() {
        ui.value = ui.value.copy(busy = false, priceText = "", error = null, entry = null)
        refresh()
    }

    private fun applyRead(list: List<Refuel>) {
        val shown = ui.value
        waiting = list
        val target = refuelPromptTarget(
            RefuelPromptInput(
                loggedIn = true,
                pending = list,
                deferred = deferred,
                onFormScreen = onFormScreen(),
                busy = shown.busy,
            ),
        )
        // While our own write is in flight the dialog stays exactly as it is.
        val next = if (shown.busy) shown.entry else target
        pendingFormId?.let { id ->
            waiting.firstOrNull { it.id == id }?.let { found ->
                pendingFormId = null
                ui.value = ui.value.copy(formRequest = found)
            }
        }
        ui.value = if (next?.id == shown.entry?.id) {
            ui.value.copy(loading = false, entry = next)
        } else {
            ui.value.copy(loading = false, entry = next, priceText = "", error = null)
        }
        // The prompt takes over: the same question must not also beep as a
        // notification while the user is looking at the dialog (docs/SPEC.md §13.1).
        if (next != null && next.id != shown.entry?.id) onResolved(next.id)
    }
}
