package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.data.db.ExpenseDao
import pl.lk.toyotacompanion.data.db.ExpenseEntity

data class ExpensesUiState(val loading: Boolean = true, val expenses: List<Expense> = emptyList(), val failed: Boolean = false)

/** The non-fuel expenses stored on the phone: read, add (any date), delete. Never touches the sync or the car. */
class ExpensesController(
    private val dao: ExpenseDao,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(ExpensesUiState())
    val state: StateFlow<ExpensesUiState> = ui.asStateFlow()

    /**
     * Reads and writes run one after another in the order of the taps (C22 D6): two quick adds or deletes can no longer
     * let an older list land after a newer one, and a failed write is followed by nothing that hides it.
     */
    private val queue = Mutex()

    /** Bumped by [reset]; an operation queued before a logout does not write its list afterwards. */
    private var generation = 0

    fun load() {
        val startedIn = generation
        scope.launch { queue.withLock { reload(startedIn) } }
    }

    /** True when the entry was accepted; the amount is validated here so the form only shows "invalid". */
    fun add(date: LocalDate, category: ExpenseCategory, amountText: String, note: String): Boolean {
        val amount = parseExpenseAmount(amountText) ?: return false
        val entity = ExpenseEntity(UUID.randomUUID().toString(), date.toEpochDay(), category.name, amount, note.trim().ifEmpty { null })
        write { dao.upsert(entity) }
        return true
    }

    fun delete(id: String) {
        write { dao.delete(id) }
    }

    /** One write in the queue, then the list again; a failed write keeps the rows on screen and marks the failure. */
    private fun write(change: suspend () -> Unit) {
        val startedIn = generation
        scope.launch {
            queue.withLock {
                try {
                    withContext(io) { change() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == startedIn) ui.value = ui.value.copy(failed = true)
                    return@withLock
                }
                reload(startedIn)
            }
        }
    }

    private suspend fun reload(startedIn: Int) {
        val next = try {
            val rows = withContext(io) { dao.all() }.map { it.toExpense() }
            ExpensesUiState(loading = false, expenses = rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExpensesUiState(loading = false, failed = true)
        }
        if (generation == startedIn) ui.value = next
    }

    fun reset() {
        generation++
        ui.value = ExpensesUiState()
    }
}

/** A stored row as the screens see it; an unknown category name (from a newer version) reads as [ExpenseCategory.OTHER]. */
internal fun ExpenseEntity.toExpense(): Expense = Expense(
    id,
    LocalDate.ofEpochDay(dateEpochDay),
    ExpenseCategory.entries.firstOrNull { it.name == category } ?: ExpenseCategory.OTHER,
    amountPln,
    note,
)
