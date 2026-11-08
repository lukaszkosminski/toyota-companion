package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

private val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
private val TODAY: LocalDate = LocalDate.of(2026, 11, 8)

private fun drive(id: String, start: String, km: Double, fuel: String, price: String?): StoredTrip {
    val begin = Instant.parse(start)
    val record = TripRecord(Trip(id, begin, begin.plusSeconds(1_800), km, BigDecimal(fuel), null), "VINAAA111")
    return StoredTrip(record, price?.let { TripCost(id, BigDecimal(it), null, null, CostSource.REFUEL) })
}

private fun spend(id: String, date: String, category: ExpenseCategory, amount: String) =
    Expense(id, LocalDate.parse(date), category, BigDecimal(amount), null)

private val TRIPS = listOf(
    drive("a", "2025-05-10T08:00:00Z", 100.0, "4.0", "25.00"),
    drive("b", "2025-06-10T08:00:00Z", 100.0, "6.0", "40.00"),
    drive("c", "2026-05-10T08:00:00Z", 100.0, "4.5", "30.00"),
    drive("d", "2026-05-11T08:00:00Z", 250.0, "10.0", "70.00"),
    drive("e", "2026-06-10T08:00:00Z", 100.0, "6.0", null),
    drive("f", "2026-07-01T08:00:00Z", 20.0, "1.0", "6.00"),
)

private val EXPENSES = listOf(
    spend("x1", "2026-03-15", ExpenseCategory.SERVICE, "500.00"),
    spend("x2", "2026-05-20", ExpenseCategory.TYRES, "800.50"),
    spend("x3", "2026-08-01", ExpenseCategory.WASH, "40.00"),
    spend("x4", "2026-08-02", ExpenseCategory.FEES, "10.00"),
    spend("x5", "2025-12-01", ExpenseCategory.INSURANCE, "900.00"),
)

private fun bd(value: String) = BigDecimal(value)

private fun assertBd(expected: String, actual: BigDecimal?) {
    assertTrue(actual != null && bd(expected).compareTo(actual) == 0, "expected $expected but was $actual")
}

/** docs/SPEC.md §39: fuel and everything else in one year. */
class YearOwnershipTest {

    @Test
    fun `the year adds the priced fuel and the expenses of that year`() {
        val own = requireNotNull(yearOwnership(TRIPS, EXPENSES, 2026, ZONE, TODAY))

        assertBd("106.00", own.cost.fuelPln)
        assertBd("1350.50", own.cost.expensesPln)
        assertBd("1456.50", own.cost.totalPln)
        assertBd("3.10", own.cost.perKmPln) // 1456,50 zł over all 470 km of the year
        assertBd("309.89", own.cost.per100KmPln)
        assertBd("7", own.cost.fuelSharePct)
    }

    @Test
    fun `the monthly average starts at the first trip or expense of the year and ends this month`() {
        val own = requireNotNull(yearOwnership(TRIPS, EXPENSES, 2026, ZONE, TODAY))
        assertEquals(9, own.months) // March (first expense) to November
        assertBd("161.83", own.cost.perMonthPln)

        val before = requireNotNull(yearOwnership(TRIPS, EXPENSES, 2025, ZONE, TODAY))
        assertEquals(8, before.months) // May (first trip) to December
        assertBd("965.00", before.cost.totalPln)
    }

    @Test
    fun `the three biggest categories of the year are listed, biggest first`() {
        val own = requireNotNull(yearOwnership(TRIPS, EXPENSES, 2026, ZONE, TODAY))
        assertEquals(listOf(ExpenseCategory.TYRES, ExpenseCategory.SERVICE, ExpenseCategory.WASH), own.topCategories.map { it.category })
    }

    @Test
    fun `a year without priced trips still counts its expenses, and nothing at all is no card`() {
        val unpriced = listOf(drive("u", "2024-04-01T08:00:00Z", 50.0, "2.0", null))
        val onlyExpenses = requireNotNull(
            yearOwnership(unpriced, listOf(spend("y", "2024-09-01", ExpenseCategory.PARTS, "120.00")), 2024, ZONE, TODAY),
        )
        assertBd("120.00", onlyExpenses.cost.totalPln)
        assertNull(onlyExpenses.cost.fuelSharePct)

        assertNull(yearOwnership(unpriced, emptyList(), 2024, ZONE, TODAY))
        assertNull(yearOwnership(emptyList(), emptyList(), 2026, ZONE, TODAY))
    }

    @Test
    fun `the lines say total, rates, months, split, categories and the change`() {
        val own = yearOwnership(TRIPS, EXPENSES, 2026, ZONE, TODAY)
        val before = yearOwnership(TRIPS, EXPENSES, 2025, ZONE, TODAY)

        val lines = ownershipLines(own, before)

        assertEquals(
            listOf(
                "Koszt całkowity: ${TripsFormatting.money(bd("1456.50"))} (${TripsFormatting.money(bd("3.10"))}/km · ${TripsFormatting.money(bd("309.89"))}/100 km)",
                "Średnio ${TripsFormatting.money(bd("161.83"))} na miesiąc (z 9 miesięcy)",
                "Paliwo ${TripsFormatting.money(bd("106.00"))} (7 %) · wydatki ${TripsFormatting.money(bd("1350.50"))}",
                "Najwięcej poza paliwem: Opony ${TripsFormatting.money(bd("800.50"))}, Serwis ${TripsFormatting.money(bd("500.00"))}, " +
                    "Myjnia ${TripsFormatting.money(bd("40.00"))}",
                "Koszt całkowity wobec poprzedniego roku: +51 %",
            ),
            lines,
        )
        assertEquals(emptyList(), ownershipLines(null, before))
        assertTrue(ownershipLines(own, null).none { "poprzedniego roku" in it })
    }

    @Test
    fun `one month is said in the singular`() {
        assertEquals("Średnio 5,00 zł na miesiąc (z 1 miesiąca)", Texts.ownershipPerMonth("5,00 zł", 1))
    }

    @Test
    fun `the shared text ends with the total cost`() {
        val summary = requireNotNull(yearSummary(TRIPS, 2026, ZONE))
        val text = yearSummaryShareText(summary, yearSummary(TRIPS, 2025, ZONE), ZONE, yearOwnership(TRIPS, EXPENSES, 2026, ZONE, TODAY), null)
        assertTrue(text.lines().last().startsWith("Najwięcej poza paliwem"), text)
        assertTrue(text.lines().any { it.startsWith("Koszt całkowity: ") }, text)
    }

    @Test
    fun `the controller reads the expenses with the trips`() = runTest {
        val controller = YearSummaryController(
            read = { TRIPS },
            zone = { ZONE },
            readExpenses = { EXPENSES },
            today = { TODAY },
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            io = StandardTestDispatcher(testScheduler),
        )

        controller.load(2026)
        advanceUntilIdle()

        val state = controller.state.value
        assertBd("1456.50", state.ownership?.cost?.totalPln)
        assertBd("965.00", state.previousOwnership?.cost?.totalPln)
    }
}
