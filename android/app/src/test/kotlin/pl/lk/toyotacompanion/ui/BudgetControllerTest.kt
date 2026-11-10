package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

/** The budget controller (PRD F48, docs/SPEC.md §49): reads, saves, failures and a late answer after logout. */
private val IN_MONTH: Instant = Instant.parse("2026-10-15T10:00:00Z")

private fun stored(id: String, cost: String = "342.45"): StoredTrip = StoredTrip(
    record = TripRecord(
        trip = Trip(id, IN_MONTH, IN_MONTH.plusSeconds(1_800), 12.3, BigDecimal("0.600"), null),
        vin = "VIN1",
    ),
    cost = TripCost(id, BigDecimal(cost), BigDecimal("6.19"), "r1", CostSource.REFUEL),
)

private fun expense(id: String, amount: String = "250.50"): Expense =
    Expense(id, LocalDate.of(2026, 10, 5), ExpenseCategory.SERVICE, BigDecimal(amount), null)

private class FakeBudget {
    var limit: BigDecimal? = BigDecimal("800.00")
    var trips: List<StoredTrip> = listOf(stored("a"))
    var expenses: List<Expense> = listOf(expense("e"))
    var tripError: Exception? = null
    var tripGate: CompletableDeferred<Unit>? = null
    var tripGates = ArrayDeque<CompletableDeferred<Unit>>()
    var tripReads = 0
    var tripsForCall: (Int) -> List<StoredTrip> = { trips }
    var saveCalls = 0
    var saved: BigDecimal? = null
    var savedSet = false
    var alert = true

    /** What a read that ran before a later toggle still returns (a stale read); `null` = the stored value. */
    var staleAlert: Boolean? = null
    var alertSaveCalls = 0

    fun readLimit(): BigDecimal? = limit

    fun saveLimit(value: BigDecimal?) {
        saveCalls++
        savedSet = true
        saved = value
        limit = value
    }

    fun readAlert(): Boolean = staleAlert ?: alert

    fun saveAlert(on: Boolean) {
        alertSaveCalls++
        alert = on
    }

    suspend fun readTrips(): List<StoredTrip> {
        val call = ++tripReads
        tripGate?.await()
        tripGates.removeFirstOrNull()?.await()
        tripError?.let { throw it }
        return tripsForCall(call)
    }

    suspend fun readExpenses(): List<Expense> = expenses
}

class BudgetControllerTest {

    private fun controller(fake: FakeBudget, io: CoroutineDispatcher) = BudgetController(
        readLimit = fake::readLimit,
        saveLimit = fake::saveLimit,
        readAlert = fake::readAlert,
        saveAlert = fake::saveAlert,
        readTrips = { fake.readTrips() },
        readExpenses = { fake.readExpenses() },
        zone = { ZoneOffset.UTC },
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
        today = { LocalDate.of(2026, 10, 15) },
    )

    @Test
    fun `loading the budget computes the current month`() = runTest {
        val fake = FakeBudget()
        val c = controller(fake, StandardTestDispatcher(testScheduler))

        c.ensureLoaded()
        advanceUntilIdle()

        val state = c.state.value
        assertEquals(MonthlyBudget(BigDecimal("800.00")), state.budget)
        assertEquals(MonthKey(2026, 10), state.month)
        assertEquals(BigDecimal("592.95"), state.progress?.spentPln)
        assertEquals(BigDecimal("207.05"), state.progress?.remainingPln)
        assertEquals(74, state.progress?.percentUsed)
        assertFalse(requireNotNull(state.progress).overBudget)
        assertFalse(state.loading)
        assertFalse(state.failed)
    }

    @Test
    fun `without a limit the budget is empty`() = runTest {
        val fake = FakeBudget().apply { limit = null }
        val c = controller(fake, StandardTestDispatcher(testScheduler))

        c.ensureLoaded()
        advanceUntilIdle()

        assertFalse(c.state.value.budget.any)
        assertNull(c.state.value.progress)
    }

    @Test
    fun `setting a limit stores it and recomputes the month`() = runTest {
        val fake = FakeBudget()
        val c = controller(fake, StandardTestDispatcher(testScheduler))

        assertTrue(c.set("900,50"))
        advanceUntilIdle()

        assertEquals(1, fake.saveCalls)
        assertEquals(0, BigDecimal("900.50").compareTo(requireNotNull(fake.saved)))
        assertEquals(BigDecimal("900.50"), c.state.value.budget.limitPln)
        assertEquals(BigDecimal("900.50"), c.state.value.progress?.limitPln)
    }

    @Test
    fun `an invalid limit changes nothing`() = runTest {
        val fake = FakeBudget()
        val c = controller(fake, StandardTestDispatcher(testScheduler))
        c.ensureLoaded()
        advanceUntilIdle()
        val before = c.state.value

        assertFalse(c.set("0"))
        assertFalse(c.set("abc"))
        advanceUntilIdle()

        assertEquals(0, fake.saveCalls)
        assertEquals(before, c.state.value)
    }

    @Test
    fun `an empty field removes the budget`() = runTest {
        val fake = FakeBudget()
        val c = controller(fake, StandardTestDispatcher(testScheduler))
        c.ensureLoaded()
        advanceUntilIdle()

        assertTrue(c.set(""))
        advanceUntilIdle()

        assertEquals(1, fake.saveCalls)
        assertTrue(fake.savedSet)
        assertNull(fake.saved)
        assertFalse(c.state.value.budget.any)
        assertNull(c.state.value.progress)
    }

    @Test
    fun `a failed trip read keeps the limit and hides the figures`() = runTest {
        val fake = FakeBudget().apply { tripError = IllegalStateException("archive is unreadable") }
        val c = controller(fake, StandardTestDispatcher(testScheduler))

        c.ensureLoaded()
        advanceUntilIdle()

        assertEquals(BigDecimal("800.00"), c.state.value.budget.limitPln)
        assertNull(c.state.value.progress)
        assertEquals(MonthKey(2026, 10), c.state.value.month)
        assertTrue(c.state.value.failed)
        assertFalse(c.state.value.loading)
    }

    @Test
    fun `a failed refresh keeps the last good figures of the same month`() = runTest {
        val fake = FakeBudget()
        val c = controller(fake, StandardTestDispatcher(testScheduler))
        c.ensureLoaded()
        advanceUntilIdle()
        val good = requireNotNull(c.state.value.progress)

        fake.tripError = IllegalStateException("archive is unreadable")
        c.refresh()
        advanceUntilIdle()

        assertEquals(good, c.state.value.progress)
        assertEquals(BigDecimal("800.00"), c.state.value.budget.limitPln)
        assertTrue(c.state.value.failed)
        assertFalse(c.state.value.loading)
    }

    @Test
    fun `the stored limit survives reset and is read again`() = runTest {
        val fake = FakeBudget()
        val c = controller(fake, StandardTestDispatcher(testScheduler))
        c.ensureLoaded()
        advanceUntilIdle()

        c.reset()
        assertEquals(BudgetState(), c.state.value)
        assertEquals(0, fake.saveCalls, "reset never changes the phone setting")

        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(BigDecimal("800.00"), c.state.value.budget.limitPln)
    }

    @Test
    fun `reset drops the figures and a late answer`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeBudget().apply { tripGate = gate }
        val c = controller(fake, StandardTestDispatcher(testScheduler))

        c.ensureLoaded()
        advanceUntilIdle()
        c.reset()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(BudgetState(), c.state.value)
    }

    @Test
    fun `the budget alert switch is read and saved`() = runTest {
        val fake = FakeBudget().apply { alert = false }
        val c = controller(fake, StandardTestDispatcher(testScheduler))
        c.ensureLoaded()
        advanceUntilIdle()

        assertFalse(c.state.value.alertEnabled)

        c.setAlert(true)

        assertEquals(1, fake.alertSaveCalls)
        assertTrue(fake.alert)
        assertTrue(c.state.value.alertEnabled)
    }

    @Test
    fun `a failed read keeps the alert switch`() = runTest {
        val fake = FakeBudget().apply { alert = false; tripError = IllegalStateException("db") }
        val c = controller(fake, StandardTestDispatcher(testScheduler))

        c.ensureLoaded()
        advanceUntilIdle()

        assertFalse(c.state.value.alertEnabled)
        assertTrue(c.state.value.failed)
    }

    @Test
    fun `a refresh that was reading cannot undo the alert switch`() = runTest {
        val fake = FakeBudget().apply { staleAlert = true }
        val gate = CompletableDeferred<Unit>()
        fake.tripGate = gate
        val c = controller(fake, StandardTestDispatcher(testScheduler))

        c.refresh()
        advanceUntilIdle()
        c.setAlert(false)
        gate.complete(Unit)
        advanceUntilIdle()

        // The read saw the old switch, but the owner's tap wins; the figures of the read are kept and nothing hangs.
        assertFalse(c.state.value.alertEnabled)
        assertFalse(fake.alert)
        assertFalse(c.state.value.loading)
        assertEquals(BigDecimal("592.95"), c.state.value.progress?.spentPln)
    }

    @Test
    fun `a slower first refresh never overwrites a newer one`() = runTest {
        val fake = FakeBudget()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        fake.tripGates.add(first)
        fake.tripGates.add(second)
        fake.tripsForCall = { call -> if (call == 1) emptyList() else listOf(stored("a")) }
        val c = controller(fake, StandardTestDispatcher(testScheduler))

        c.refresh()
        advanceUntilIdle()
        c.refresh()
        advanceUntilIdle()

        second.complete(Unit)
        advanceUntilIdle()
        assertEquals(BigDecimal("592.95"), c.state.value.progress?.spentPln)

        // The older read finishes later; its token no longer matches, so its figures are dropped.
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(BigDecimal("592.95"), c.state.value.progress?.spentPln)
    }

    @Test
    fun `ensureLoaded reads once, refresh reads again`() = runTest {
        val fake = FakeBudget()
        val c = controller(fake, StandardTestDispatcher(testScheduler))

        c.ensureLoaded()
        advanceUntilIdle()
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(MonthKey(2026, 10), c.state.value.month)

        fake.trips = emptyList()
        c.refresh()
        advanceUntilIdle()
        assertEquals(BigDecimal("250.50"), c.state.value.progress?.spentPln)
    }
}
