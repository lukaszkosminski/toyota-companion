package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip

/**
 * The owner's monthly budget (PRD F48, docs/SPEC.md §49): the limit from the settings and the month in progress,
 * computed from the local archive and the expenses table. No network, no Toyota call, so the car is never woken.
 * [reset] on logout drops the figures; the limit itself belongs to the phone and stays.
 *
 * @param readLimit the stored limit, `null` when there is none
 * @param saveLimit stores or removes the limit
 * @param readAlert / [saveAlert] the budget-notification switch (PRD F49, docs/SPEC.md §49)
 * @param readTrips the trips of the selected vehicle (`SyncEngine.trips`, docs/SPEC.md §10.1)
 * @param readExpenses the stored non-fuel expenses (`ExpenseDao.all`, docs/SPEC.md §38)
 * @param zone the zone of the month and of the trips, the same as the list
 * @param today the day the current month is taken from; injected for tests
 */
class BudgetController(
    private val readLimit: () -> BigDecimal?,
    private val saveLimit: (BigDecimal?) -> Unit,
    private val readAlert: () -> Boolean,
    private val saveAlert: (Boolean) -> Unit,
    private val readTrips: suspend () -> List<StoredTrip>,
    private val readExpenses: suspend () -> List<Expense>,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
) {
    private val ui = MutableStateFlow(BudgetState())
    val state: StateFlow<BudgetState> = ui.asStateFlow()

    // refresh() is also called from the synchronization thread, so the token must be atomic.
    private val generation = AtomicInteger()
    private var loadStarted = false

    fun ensureLoaded() {
        if (!loadStarted) refresh()
    }

    /** Reads the limit, the trips and the expenses again; the figures follow from the current month. */
    fun refresh() {
        loadStarted = true
        // Every call gets its own token: a slow first read must not overwrite a fresher second one (C26 W3).
        val token = generation.incrementAndGet()
        val previous = ui.value
        // The last good figures stay on screen while a retry runs (the lesson of C26).
        ui.value = previous.copy(loading = true, failed = false)
        scope.launch {
            val month = monthOf(today())
            val next = try {
                withContext(io) {
                    val budget = MonthlyBudget(readLimit())
                    BudgetState(
                        budget = budget,
                        progress = budgetProgressFor(budget, readTrips(), readExpenses(), zone(), month),
                        month = month,
                        loading = false,
                        failed = false,
                        alertEnabled = readAlert(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed read keeps the limit (it is a phone setting) and the last good figures of the same month;
                // no invented zeros.
                val budget = try {
                    withContext(io) { MonthlyBudget(readLimit()) }
                } catch (inner: CancellationException) {
                    throw inner
                } catch (inner: Exception) {
                    MonthlyBudget()
                }
                val alertEnabled = try {
                    withContext(io) { readAlert() }
                } catch (inner: CancellationException) {
                    throw inner
                } catch (inner: Exception) {
                    previous.alertEnabled
                }
                BudgetState(
                    budget = budget,
                    progress = previous.progress?.takeIf { previous.month == month },
                    month = month,
                    loading = false,
                    failed = true,
                    alertEnabled = alertEnabled,
                )
            }
            if (token != generation.get()) return@launch
            // The switch may have been flipped while the read ran; the current value wins, the figures stay.
            ui.update { current ->
                if (current.alertEnabled == previous.alertEnabled) next else next.copy(alertEnabled = current.alertEnabled)
            }
        }
    }

    /**
     * Saves the typed limit and recomputes the month; returns false and changes nothing when the text is invalid.
     * An empty field removes the budget. The write is a single `SharedPreferences` edit on the caller's thread, like
     * the other settings (docs/SPEC.md §12.2).
     */
    fun set(text: String): Boolean {
        val limit = when (val check = parseBudgetLimit(text)) {
            is BudgetLimitCheck.Valid -> check.value
            BudgetLimitCheck.Empty -> null
            BudgetLimitCheck.Invalid -> return false
        }
        saveLimit(limit)
        ui.value = ui.value.copy(budget = MonthlyBudget(limit), progress = null)
        refresh()
        return true
    }

    /** Stores the budget-notification switch; the figures and the alert rule do not change. */
    fun setAlert(on: Boolean) {
        saveAlert(on)
        ui.update { it.copy(alertEnabled = on) }
    }

    fun reset() {
        generation.incrementAndGet()
        loadStarted = false
        ui.value = BudgetState()
    }
}
