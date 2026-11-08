package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
private val OCTOBER = MonthKey(2026, 10)
private val TODAY: LocalDate = LocalDate.parse("2026-10-20")

private fun trip(id: String, start: String, km: Double, fuel: String, ev: Double? = null) = StoredTrip(
    TripRecord(Trip(id, Instant.parse(start), Instant.parse(start).plusSeconds(3_600), km, BigDecimal(fuel), ev), "VINAAA111"),
    null,
)

/** docs/SPEC.md §40: the goals typed by the owner and the figures of a month against them. */
class EcoGoalsTest {
    @Test
    fun `an electric goal is a whole percent from 1 to 100, with or without the sign`() {
        assertEquals(EvGoalCheck.Valid(40), parseEvGoal("40"))
        assertEquals(EvGoalCheck.Valid(40), parseEvGoal(" 40 % "))
        assertEquals(EvGoalCheck.Valid(1), parseEvGoal("1"))
        assertEquals(EvGoalCheck.Valid(100), parseEvGoal("100"))
    }

    @Test
    fun `an empty field removes the goal, anything else wrong is invalid and never clamped`() {
        assertEquals(EvGoalCheck.Empty, parseEvGoal(""))
        assertEquals(EvGoalCheck.Empty, parseEvGoal("   "))
        for (bad in listOf("0", "101", "-5", "4,5", "40.5", "abc", "%", "1000", "40 km")) {
            assertEquals(EvGoalCheck.Invalid, parseEvGoal(bad), bad)
        }
    }

    @Test
    fun `consumption is the sum of fuel over the sum of kilometres of the calendar month in the phone zone`() {
        val goals = EcoGoals(maxConsumption = BigDecimal("4.50"))
        val trips = listOf(
            trip("a", "2026-10-05T10:00:00Z", 100.0, "4.0"),
            trip("b", "2026-10-06T10:00:00Z", 50.0, "2.5"),
            // 00:30 on 1 October in Warsaw (UTC+2), still September in UTC.
            trip("c", "2026-09-30T22:30:00Z", 50.0, "3.0"),
            // September in Warsaw.
            trip("d", "2026-09-30T21:30:00Z", 500.0, "50.0"),
        )
        val month = ecoMonth(goals, trips, WARSAW, OCTOBER)
        assertEquals(BigDecimal("200.0"), month.km)
        assertEquals(BigDecimal("4.75"), month.consumption?.actual)
        assertEquals(false, month.consumption?.met)
        assertTrue(month.enoughDriving)
    }

    @Test
    fun `a goal equal to the figure is met`() {
        val month = ecoMonth(EcoGoals(maxConsumption = BigDecimal("4.00")), listOf(trip("a", "2026-10-05T10:00:00Z", 100.0, "4.0")), WARSAW, OCTOBER)
        assertEquals(BigDecimal("4.00"), month.consumption?.actual)
        assertEquals(true, month.consumption?.met)
    }

    @Test
    fun `under 50 km nothing is judged`() {
        val goals = EcoGoals(BigDecimal("4.50"), 40)
        val month = ecoMonth(goals, listOf(trip("a", "2026-10-05T10:00:00Z", 49.9, "1.0", ev = 40.0)), WARSAW, OCTOBER)
        assertFalse(month.enoughDriving)
        assertEquals(BigDecimal("49.9"), month.km)
        assertNull(month.consumption?.met)
        assertNull(month.ev?.met)
        assertTrue(ecoMonth(goals, listOf(trip("a", "2026-10-05T10:00:00Z", 50.0, "1.0", ev = 40.0)), WARSAW, OCTOBER).enoughDriving)
    }

    @Test
    fun `the electric share clips each trip to its own distance and rounds to a whole percent`() {
        val goals = EcoGoals(minEvSharePct = 50)
        val trips = listOf(
            trip("a", "2026-10-05T10:00:00Z", 100.0, "3.0", ev = 150.0),
            trip("b", "2026-10-06T10:00:00Z", 100.0, "4.0", ev = 0.0),
            trip("c", "2026-10-07T10:00:00Z", 100.0, "4.0", ev = null),
        )
        val month = ecoMonth(goals, trips, WARSAW, OCTOBER)
        // 100 clipped from 150, over 300 km.
        assertEquals(BigDecimal("33"), month.ev?.actual)
        assertEquals(false, month.ev?.met)
        assertTrue(month.evKnown)
    }

    @Test
    fun `no electric data is not zero percent, but zero electric km with data is`() {
        val goals = EcoGoals(minEvSharePct = 10)
        val none = ecoMonth(goals, listOf(trip("a", "2026-10-05T10:00:00Z", 100.0, "4.0", ev = null)), WARSAW, OCTOBER)
        assertFalse(none.evKnown)
        assertNull(none.ev?.actual)
        assertNull(none.ev?.met)
        val zero = ecoMonth(goals, listOf(trip("a", "2026-10-05T10:00:00Z", 100.0, "4.0", ev = 0.0)), WARSAW, OCTOBER)
        assertTrue(zero.evKnown)
        assertEquals(BigDecimal("0"), zero.ev?.actual)
        assertEquals(false, zero.ev?.met)
    }

    @Test
    fun `broken trips are left out`() {
        val goals = EcoGoals(maxConsumption = BigDecimal("5.00"))
        val trips = listOf(
            trip("ok", "2026-10-05T10:00:00Z", 100.0, "4.0"),
            trip("zero", "2026-10-05T11:00:00Z", 0.0, "1.0"),
            trip("nan", "2026-10-05T12:00:00Z", Double.NaN, "1.0"),
            trip("neg", "2026-10-05T13:00:00Z", 10.0, "-1.0"),
        )
        val month = ecoMonth(goals, trips, WARSAW, OCTOBER)
        assertEquals(BigDecimal("100.0"), month.km)
        assertEquals(BigDecimal("4.00"), month.consumption?.actual)
    }

    @Test
    fun `an empty month has no figure`() {
        val month = ecoMonth(EcoGoals(BigDecimal("4.5"), 40), emptyList(), WARSAW, OCTOBER)
        assertEquals(BigDecimal("0.0"), month.km)
        assertFalse(month.enoughDriving)
        assertNull(month.consumption?.actual)
        assertNull(month.ev?.actual)
    }

    @Test
    fun `the previous month rolls over the year`() {
        assertEquals(MonthKey(2026, 9), previousMonth(MonthKey(2026, 10)))
        assertEquals(MonthKey(2025, 12), previousMonth(MonthKey(2026, 1)))
    }

    @Test
    fun `without a goal there is no progress, without trips last month is left out`() {
        val trips = listOf(trip("a", "2026-10-05T10:00:00Z", 100.0, "4.0"))
        assertNull(ecoProgress(EcoGoals(), trips, WARSAW, TODAY))
        val progress = requireNotNull(ecoProgress(EcoGoals(BigDecimal("4.5")), trips, WARSAW, TODAY))
        assertNull(progress.previous)
        val both = requireNotNull(ecoProgress(EcoGoals(BigDecimal("4.5")), trips + trip("s", "2026-09-10T10:00:00Z", 100.0, "4.0"), WARSAW, TODAY))
        assertEquals(MonthKey(2026, 9), both.previous?.month)
    }

    @Test
    fun `the card lines show the figures with a mark`() {
        val trips = listOf(trip("a", "2026-10-05T10:00:00Z", 100.0, "4.3", ev = 42.0))
        val progress = requireNotNull(ecoProgress(EcoGoals(BigDecimal("4.5"), 40), trips, WARSAW, TODAY))
        assertEquals(listOf("Spalanie: 4,30 / 4,50 l/100 km ✓", "Jazda na prądzie: 42 / 40 % ✓"), ecoCurrentLines(progress))
        val missed = requireNotNull(ecoProgress(EcoGoals(BigDecimal("4.0"), 50), trips, WARSAW, TODAY))
        assertEquals(listOf("Spalanie: 4,30 / 4,00 l/100 km ✗", "Jazda na prądzie: 42 / 50 % ✗"), ecoCurrentLines(missed))
    }

    @Test
    fun `a month without enough driving says so instead of a verdict`() {
        val trips = listOf(trip("a", "2026-10-05T10:00:00Z", 32.4, "1.0"))
        val progress = requireNotNull(ecoProgress(EcoGoals(BigDecimal("4.5")), trips, WARSAW, TODAY))
        assertEquals(listOf("Za mało jazdy, by ocenić (32 km)"), ecoCurrentLines(progress))
        assertEquals("Cel: za mało jazdy (32 km)", ecoWidgetLine(progress))
    }

    @Test
    fun `no electric data shows that line instead of a number`() {
        val trips = listOf(trip("a", "2026-10-05T10:00:00Z", 100.0, "4.0"))
        val progress = requireNotNull(ecoProgress(EcoGoals(minEvSharePct = 40), trips, WARSAW, TODAY))
        assertEquals(listOf("Jazda na prądzie: brak danych w tym miesiącu"), ecoCurrentLines(progress))
        assertEquals("", ecoWidgetLine(progress))
    }

    @Test
    fun `the previous month is reported as met or missed per goal`() {
        val trips = listOf(
            trip("sep", "2026-09-10T10:00:00Z", 100.0, "4.0", ev = 30.0),
            trip("oct", "2026-10-05T10:00:00Z", 100.0, "4.0"),
        )
        val progress = requireNotNull(ecoProgress(EcoGoals(BigDecimal("4.5"), 40), trips, WARSAW, TODAY))
        assertEquals("Poprzedni miesiąc: cel spalania – osiągnięty · cel jazdy na prądzie – nieosiągnięty", ecoPreviousLine(progress))
    }

    @Test
    fun `a thin previous month is not judged`() {
        val trips = listOf(trip("sep", "2026-09-10T10:00:00Z", 20.0, "1.0"), trip("oct", "2026-10-05T10:00:00Z", 100.0, "4.0"))
        val progress = requireNotNull(ecoProgress(EcoGoals(BigDecimal("4.5")), trips, WARSAW, TODAY))
        assertEquals("Poprzedni miesiąc: za mało jazdy, by ocenić (20 km)", ecoPreviousLine(progress))
    }

    @Test
    fun `the widget line is empty without a goal and carries both goals when set`() {
        assertEquals("", ecoWidgetLine(null))
        val trips = listOf(trip("a", "2026-10-05T10:00:00Z", 100.0, "4.3", ev = 42.0))
        val progress = ecoProgress(EcoGoals(BigDecimal("4.5"), 40), trips, WARSAW, TODAY)
        assertEquals("Cel: 4,30 / 4,50 l/100 km ✓ · prąd 42 / 40 % ✓", ecoWidgetLine(progress))
        val only = ecoProgress(EcoGoals(BigDecimal("4.0")), trips, WARSAW, TODAY)
        assertEquals("Cel: 4,30 / 4,00 l/100 km ✗", ecoWidgetLine(only))
    }

    @Test
    fun `the lines speak English too`() {
        val trips = listOf(trip("a", "2026-10-05T10:00:00Z", 100.0, "4.3", ev = 42.0), trip("s", "2026-09-10T10:00:00Z", 100.0, "4.0", ev = 30.0))
        val progress = requireNotNull(ecoProgress(EcoGoals(BigDecimal("4.5"), 40), trips, WARSAW, TODAY))
        Lang.current = AppLanguage.EN
        try {
            assertEquals(listOf("Consumption: 4,30 / 4,50 l/100 km ✓", "Electric driving: 42 / 40 % ✓"), ecoCurrentLines(progress))
            assertEquals("Goal: 4,30 / 4,50 l/100 km ✓ · electric 42 / 40 % ✓", ecoWidgetLine(progress))
            assertEquals("Previous month: consumption goal – met · electric driving goal – missed", ecoPreviousLine(progress))
        } finally {
            Lang.current = AppLanguage.PL
        }
    }
}
