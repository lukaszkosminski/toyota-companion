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

    fun load() {
        scope.launch { reload() }
    }

    /** True when the entry was accepted; the amount is validated here so the form only shows "invalid". */
    fun add(date: LocalDate, category: ExpenseCategory, amountText: String, note: String): Boolean {
        val amount = parseExpenseAmount(amountText) ?: return false
        val entity = ExpenseEntity(UUID.randomUUID().toString(), date.toEpochDay(), category.name, amount, note.trim().ifEmpty { null })
        scope.launch {
            try {
                withContext(io) { dao.upsert(entity) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.value = ui.value.copy(failed = true)
                return@launch
            }
            reload()
        }
        return true
    }

    fun delete(id: String) {
        scope.launch {
            try {
                withContext(io) { dao.delete(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.value = ui.value.copy(failed = true)
                return@launch
            }
            reload()
        }
    }

    private suspend fun reload() {
        ui.value = try {
            val rows = withContext(io) { dao.all() }.map {
                Expense(
                    it.id,
                    LocalDate.ofEpochDay(it.dateEpochDay),
                    ExpenseCategory.entries.firstOrNull { c -> c.name == it.category } ?: ExpenseCategory.OTHER,
                    it.amountPln,
                    it.note,
                )
            }
            ExpensesUiState(loading = false, expenses = rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExpensesUiState(loading = false, failed = true)
        }
    }

    fun reset() {
        ui.value = ExpensesUiState()
    }
}
