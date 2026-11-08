package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.data.db.ExpenseDao
import pl.lk.toyotacompanion.data.db.ExpenseEntity

/** The table behind the controller: an in-memory list that fails on demand. */
private class FakeExpenseDao : ExpenseDao() {
    val rows = LinkedHashMap<String, ExpenseEntity>()
    var failRead = false
    var failWrite = false
    var reads = 0

    override suspend fun upsert(entity: ExpenseEntity) {
        if (failWrite) throw IllegalStateException("disk full")
        rows[entity.id] = entity
    }

    override suspend fun insertAllIfAbsent(entities: List<ExpenseEntity>) {
        for (e in entities) rows.putIfAbsent(e.id, e)
    }

    override suspend fun all(): List<ExpenseEntity> {
        reads++
        if (failRead) throw IllegalStateException("unreadable")
        return rows.values.sortedWith(compareByDescending<ExpenseEntity> { it.dateEpochDay }.thenBy { it.id })
    }

    override suspend fun delete(id: String) {
        if (failWrite) throw IllegalStateException("disk full")
        rows.remove(id)
    }
}

private fun entity(id: String, day: Long, category: String = "SERVICE", amount: String = "100.00", note: String? = null) =
    ExpenseEntity(id, day, category, BigDecimal(amount), note)

/** docs/SPEC.md §38: the controller of the "Wydatki" screen on a fake [ExpenseDao]. */
class ExpensesControllerTest {
    private fun TestScope.controller(dao: ExpenseDao): ExpensesController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ExpensesController(dao, CoroutineScope(SupervisorJob() + dispatcher), dispatcher)
    }

    @Test
    fun `it starts loading and shows the stored rows, newest day first`() = runTest {
        val dao = FakeExpenseDao().apply {
            rows["a"] = entity("a", 20_000, "TYRES", "1 200.00".replace(" ", ""))
            rows["b"] = entity("b", 20_010, "NOT_A_CATEGORY", "50.00", note = "x")
        }
        val c = controller(dao)
        assertTrue(c.state.value.loading)

        c.load()
        advanceUntilIdle()

        val state = c.state.value
        assertFalse(state.loading)
        assertFalse(state.failed)
        assertEquals(listOf("b", "a"), state.expenses.map { it.id })
        // An unknown stored category reads as OTHER, never as a crash.
        assertEquals(ExpenseCategory.OTHER, state.expenses[0].category)
        assertEquals(ExpenseCategory.TYRES, state.expenses[1].category)
        assertEquals(LocalDate.ofEpochDay(20_010), state.expenses[0].date)
        assertEquals("x", state.expenses[0].note)
    }

    @Test
    fun `an empty table is an empty list, not a failure`() = runTest {
        val c = controller(FakeExpenseDao())
        c.load()
        advanceUntilIdle()
        assertFalse(c.state.value.loading)
        assertFalse(c.state.value.failed)
        assertEquals(emptyList(), c.state.value.expenses)
    }

    @Test
    fun `an unreadable table is a failure`() = runTest {
        val c = controller(FakeExpenseDao().apply { failRead = true })
        c.load()
        advanceUntilIdle()
        assertFalse(c.state.value.loading)
        assertTrue(c.state.value.failed)
    }

    @Test
    fun `a valid expense is stored with a trimmed note and shown`() = runTest {
        val dao = FakeExpenseDao()
        val c = controller(dao)

        val accepted = c.add(LocalDate.ofEpochDay(20_000), ExpenseCategory.INSPECTION, "1 234,50", "  przegląd  ")
        advanceUntilIdle()

        assertTrue(accepted)
        val stored = dao.rows.values.single()
        assertEquals(BigDecimal("1234.50"), stored.amountPln)
        assertEquals("INSPECTION", stored.category)
        assertEquals(20_000L, stored.dateEpochDay)
        assertEquals("przegląd", stored.note)
        assertEquals(1, c.state.value.expenses.size)
        assertEquals(ExpenseCategory.INSPECTION, c.state.value.expenses.single().category)
    }

    @Test
    fun `a blank note is stored as no note`() = runTest {
        val dao = FakeExpenseDao()
        val c = controller(dao)
        c.add(LocalDate.ofEpochDay(20_000), ExpenseCategory.WASH, "35", "   ")
        advanceUntilIdle()
        assertNull(dao.rows.values.single().note)
    }

    @Test
    fun `an invalid amount is refused and nothing is written`() = runTest {
        val dao = FakeExpenseDao()
        val c = controller(dao)

        for (text in listOf("", "0", "-5", "12345678", "abc")) {
            assertFalse(c.add(LocalDate.ofEpochDay(20_000), ExpenseCategory.OTHER, text, ""))
        }
        advanceUntilIdle()

        assertTrue(dao.rows.isEmpty())
        assertEquals(0, dao.reads)
    }

    @Test
    fun `two entries get different ids`() = runTest {
        val dao = FakeExpenseDao()
        val c = controller(dao)
        c.add(LocalDate.ofEpochDay(20_000), ExpenseCategory.PARTS, "10", "")
        c.add(LocalDate.ofEpochDay(20_000), ExpenseCategory.PARTS, "10", "")
        advanceUntilIdle()
        assertEquals(2, dao.rows.size)
        assertEquals(2, c.state.value.expenses.size)
    }

    @Test
    fun `a write that fails marks the failure and keeps what was stored`() = runTest {
        val dao = FakeExpenseDao().apply { rows["a"] = entity("a", 20_000) }
        val c = controller(dao)
        c.load()
        advanceUntilIdle()

        dao.failWrite = true
        assertTrue(c.add(LocalDate.ofEpochDay(20_001), ExpenseCategory.FEES, "20", ""))
        c.delete("a")
        advanceUntilIdle()

        assertTrue(c.state.value.failed)
        assertEquals(listOf("a"), dao.rows.keys.toList())
        assertEquals(listOf("a"), c.state.value.expenses.map { it.id })
    }

    @Test
    fun `delete removes the row and reloads`() = runTest {
        val dao = FakeExpenseDao().apply {
            rows["a"] = entity("a", 20_000)
            rows["b"] = entity("b", 20_001)
        }
        val c = controller(dao)
        c.load()
        advanceUntilIdle()

        c.delete("b")
        advanceUntilIdle()

        assertEquals(listOf("a"), dao.rows.keys.toList())
        assertEquals(listOf("a"), c.state.value.expenses.map { it.id })
        assertFalse(c.state.value.failed)
    }

    @Test
    fun `reset forgets the list and returns to loading`() = runTest {
        val dao = FakeExpenseDao().apply { rows["a"] = entity("a", 20_000) }
        val c = controller(dao)
        c.load()
        advanceUntilIdle()
        assertEquals(1, c.state.value.expenses.size)

        c.reset()

        assertTrue(c.state.value.loading)
        assertEquals(emptyList(), c.state.value.expenses)
        assertFalse(c.state.value.failed)
    }
}
