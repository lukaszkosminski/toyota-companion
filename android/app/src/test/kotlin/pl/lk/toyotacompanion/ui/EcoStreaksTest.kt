package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/** The last full month is September 2026 when today is 20 October 2026. */
private val TODAY: LocalDate = LocalDate.parse("2026-10-20")

private fun trip(id: String, start: String, km: Double, fuel: String, ev: Double? = null) = StoredTrip(
    TripRecord(Trip(id, Instant.parse(start), Instant.parse(start).plusSeconds(3_600), km, BigDecimal(fuel), ev), "VINAAA111"),
    null,
)

/** One trip in the middle of a month: consumption = fuel / km * 100, electric share = ev / km. */
private fun month(year: Int, month: Int, fuel: String, km: Double = 100.0, ev: Double? = null): StoredTrip = trip(
    "$year-$month-$fuel-$km-$ev",
    "%04d-%02d-10T10:00:00Z".format(year, month),
    km,
    fuel,
    ev,
)

private fun history(goals: EcoGoals, trips: List<StoredTrip>, today: LocalDate = TODAY): EcoHistory =
    assertNotNull(ecoHistory(goals, trips, WARSAW, today))

/** docs/SPEC.md §58: streaks and records of the eco goals, from the local archive only. */
class EcoStreaksTest {
    @Test
    fun `a streak counts the consecutive months with the goal met and ends at the last full month`() {
        val trips = listOf(
            month(2026, 6, "4.0"),
            month(2026, 7, "4.0"),
            month(2026, 8, "4.0"),
            month(2026, 9, "4.0"),
            // October is the month in progress: it neither counts nor is shown in the streak.
            month(2026, 10, "9.0"),
        )
        val h = history(EcoGoals(BigDecimal("5.00")), trips)
        assertEquals(EcoStreakCount(4, 4), h.consumptionStreak)
        assertNull(h.evStreak)
    }

    @Test
    fun `the phone zone decides the month, 00-30 on 1 October in Warsaw is October and not September`() {
        val trips = listOf(
            month(2026, 6, "4.0"),
            month(2026, 7, "4.0"),
            month(2026, 8, "4.0"),
            month(2026, 9, "4.0"),
            // 22:30 UTC on 30 September is 00:30 on 1 October in Warsaw. In UTC September would be 6.50 l/100 km and break the streak.
            trip("late", "2026-09-30T22:30:00Z", 100.0, "9.0"),
        )
        assertEquals(EcoStreakCount(4, 4), history(EcoGoals(BigDecimal("5.00")), trips).consumptionStreak)
    }

    @Test
    fun `a month under 50 km is skipped, so it neither breaks nor extends a streak`() {
        val trips = listOf(
            month(2026, 6, "4.0"),
            // Short and over the goal: judged by nobody.
            month(2026, 7, "9.0", km = 20.0),
            month(2026, 8, "4.0"),
            month(2026, 9, "4.0"),
        )
        // June, August and September: a short July did not break the streak, and it does not count as a month either.
        assertEquals(EcoStreakCount(3, 3), history(EcoGoals(BigDecimal("5.00")), trips).consumptionStreak)
    }

    @Test
    fun `a month without an electric figure is skipped`() {
        val trips = listOf(
            month(2026, 6, "4.0", ev = 60.0),
            // No trip of July reports electric driving: no data is not a missed goal.
            month(2026, 7, "4.0"),
            month(2026, 8, "4.0", ev = 60.0),
            month(2026, 9, "4.0", ev = 60.0),
        )
        assertEquals(EcoStreakCount(3, 3), history(EcoGoals(minEvSharePct = 40), trips).evStreak)
    }

    @Test
    fun `a missed month breaks the streak and the longest one stays`() {
        val trips = listOf(
            month(2026, 6, "4.0"),
            month(2026, 7, "4.0"),
            month(2026, 8, "4.0"),
            month(2026, 9, "9.0"),
        )
        assertEquals(EcoStreakCount(0, 3), history(EcoGoals(BigDecimal("5.00")), trips).consumptionStreak)
    }

    @Test
    fun `a goal change counts only the months from the change on`() {
        val trips = listOf(
            month(2026, 6, "4.0"),
            month(2026, 7, "4.0"),
            month(2026, 8, "4.0"),
            month(2026, 9, "4.0"),
        )
        // Changed in August: June and July were judged against an earlier goal, so they are not in this streak.
        val goals = EcoGoals(BigDecimal("5.00"), consumptionSince = MonthKey(2026, 8))
        assertEquals(EcoStreakCount(2, 2), history(goals, trips).consumptionStreak)
    }

    @Test
    fun `the electric goal is counted apart from the consumption goal`() {
        val trips = listOf(
            month(2026, 6, "4.0", ev = 50.0),
            month(2026, 7, "4.0", ev = 50.0),
            month(2026, 8, "4.0", ev = 50.0),
            // Consumption missed, electric share met.
            month(2026, 9, "9.0", ev = 50.0),
        )
        val h = history(EcoGoals(BigDecimal("5.00"), 40), trips)
        assertEquals(EcoStreakCount(0, 3), h.consumptionStreak)
        assertEquals(EcoStreakCount(4, 4), h.evStreak)
    }

    @Test
    fun `the lowest consumption is the earlier month of a tie, and only full months count`() {
        val trips = listOf(
            month(2026, 6, "4.0"),
            month(2026, 7, "3.0"),
            month(2026, 8, "3.0"),
            // Short month: its 1.00 l/100 km is not a record.
            month(2026, 9, "0.2", km = 20.0),
            // The month in progress is not a record either.
            month(2026, 10, "0.5"),
        )
        val h = history(EcoGoals(BigDecimal("5.00")), trips)
        assertEquals(EcoRecord(MonthKey(2026, 7), BigDecimal("3.00")), h.lowestConsumption)
        assertEquals(3, h.judgedMonths)
    }

    @Test
    fun `the records need three full months with driving`() {
        val two = history(EcoGoals(BigDecimal("5.00")), listOf(month(2026, 8, "4.0"), month(2026, 9, "4.0")))
        assertEquals(2, two.judgedMonths)
        assertNull(ecoRecordLine(two))

        val three = history(
            EcoGoals(BigDecimal("5.00")),
            listOf(month(2026, 6, "4.0"), month(2026, 8, "4.0"), month(2026, 9, "4.0")),
        )
        assertNotNull(ecoRecordLine(three))
    }

    @Test
    fun `the highest electric share is a record too`() {
        val h = history(
            EcoGoals(minEvSharePct = 40),
            listOf(
                month(2026, 6, "4.0", ev = 30.0),
                month(2026, 7, "4.0", ev = 61.0),
                month(2026, 8, "4.0", ev = 61.0),
                month(2026, 9, "4.0", ev = 20.0),
            ),
        )
        assertEquals(EcoRecord(MonthKey(2026, 7), BigDecimal(61)), h.highestEvShare)
    }

    @Test
    fun `no goal gives no history and no trips give zero streaks and no records`() {
        assertNull(ecoHistory(EcoGoals(), listOf(month(2026, 9, "4.0")), WARSAW, TODAY))

        val empty = history(EcoGoals(BigDecimal("5.00")), emptyList())
        assertEquals(EcoStreakCount(0, 0), empty.consumptionStreak)
        assertNull(empty.lowestConsumption)
        assertEquals(0, empty.judgedMonths)
        assertNull(ecoStreakLine(empty))
        assertNull(ecoRecordLine(empty))
    }

    @Test
    fun `the streak continues across the new year`() {
        val trips = listOf(month(2025, 12, "4.0"), month(2026, 1, "4.0"))
        val h = history(EcoGoals(BigDecimal("5.00")), trips, LocalDate.parse("2026-02-20"))
        assertEquals(EcoStreakCount(2, 2), h.consumptionStreak)
    }

    @Test
    fun `the streak line appears from two months and inflects the month word`() {
        val many = EcoHistory(EcoStreakCount(3, 5), null, null, null, 0)
        assertEquals("Seria: spalanie 3 miesiące (najdłuższa: 5)", ecoStreakLine(many))

        val five = EcoHistory(EcoStreakCount(5, 5), null, null, null, 0)
        assertEquals("Seria: spalanie 5 miesięcy (najdłuższa: 5)", ecoStreakLine(five))

        val one = EcoHistory(EcoStreakCount(1, 4), null, null, null, 0)
        assertEquals("Seria: spalanie 1 miesiąc (najdłuższa: 4)", ecoStreakLine(one))

        val broken = EcoHistory(EcoStreakCount(0, 2), EcoStreakCount(1, 1), null, null, 0)
        assertEquals("Seria: spalanie przerwana (najdłuższa: 2)", ecoStreakLine(broken))

        val short = EcoHistory(EcoStreakCount(1, 1), null, null, null, 0)
        assertNull(ecoStreakLine(short))
    }

    @Test
    fun `the record line shows the value and the month in the phone formatting`() {
        val h = EcoHistory(
            consumptionStreak = null,
            evStreak = null,
            lowestConsumption = EcoRecord(MonthKey(2026, 7), BigDecimal("3.92")),
            highestEvShare = EcoRecord(MonthKey(2026, 8), BigDecimal(61)),
            judgedMonths = 3,
        )
        val expected = "Rekordy: najniższe spalanie ${TripsFormatting.number(BigDecimal("3.92"), 2)} l/100 km " +
            "(${TripsFormatting.monthLabel(MonthKey(2026, 7))}) · najwyższy udział prądu 61 % " +
            "(${TripsFormatting.monthLabel(MonthKey(2026, 8))})"
        assertEquals(expected, ecoRecordLine(h))
        assertTrue(ecoRecordLine(h)!!.length < 200)
    }

    @Test
    fun `ecoMonth overload without zone produces identical results when trips belong to the month`() {
        val goals = EcoGoals(maxConsumption = BigDecimal("4.50"), minEvSharePct = 50)
        val october = MonthKey(2026, 10)
        val trips = listOf(
            month(2026, 10, "4.2", km = 60.0, ev = 35.0),
            month(2026, 10, "4.8", km = 40.0, ev = 20.0),
            trip("invalid-km", "2026-10-15T12:00:00Z", 0.0, "1.0"),
            trip("negative-fuel", "2026-10-16T12:00:00Z", 10.0, "-1.0"),
            trip("ev-capped", "2026-10-17T12:00:00Z", 20.0, "1.0", ev = 25.0),
        )

        val withZone = ecoMonth(goals, trips, WARSAW, october)
        val withoutZone = ecoMonth(goals, trips, october)

        assertEquals(withZone, withoutZone)
    }

    @Test
    fun `ecoMonth overload handles empty trips identically`() {
        val goals = EcoGoals(maxConsumption = BigDecimal("4.50"), minEvSharePct = 50)
        val october = MonthKey(2026, 10)

        val withZone = ecoMonth(goals, emptyList(), WARSAW, october)
        val withoutZone = ecoMonth(goals, emptyList(), october)

        assertEquals(withZone, withoutZone)
    }

    @Test
    fun `ecoHistory produces identical output to legacy full-scan mapping over multi-year trips`() {
        val goals = EcoGoals(
            maxConsumption = BigDecimal("4.80"),
            minEvSharePct = 45,
            consumptionSince = MonthKey(2026, 3),
            evSince = MonthKey(2026, 4),
        )
        val trips = listOf(
            month(2025, 11, "4.2", km = 120.0, ev = 60.0),
            month(2025, 12, "5.5", km = 80.0, ev = 30.0),
            month(2026, 1, "4.0", km = 95.0, ev = 50.0),
            // February 2026 is a gap month (no trips)
            month(2026, 3, "4.5", km = 150.0, ev = 70.0),
            month(2026, 4, "4.1", km = 30.0, ev = 15.0), // short month under 50km
            month(2026, 5, "4.3", km = 110.0, ev = 55.0),
            month(2026, 6, "4.4", km = 100.0, ev = 48.0),
            month(2026, 7, "4.6", km = 130.0, ev = 65.0),
            month(2026, 8, "4.7", km = 90.0, ev = 40.0),
            month(2026, 9, "4.2", km = 140.0, ev = 70.0),
            month(2026, 10, "3.9", km = 50.0, ev = 25.0), // month in progress (today is 2026-10-20)
        )

        val last = previousMonth(MonthKey(TODAY.year, TODAY.monthValue))
        val first = trips.minOf { it.record.trip.start.atZone(WARSAW).let { d -> MonthKey(d.year, d.monthValue) } }
        val allMonths = generateSequence(first) { m ->
            if (m.month == 12) MonthKey(m.year + 1, 1) else MonthKey(m.year, m.month + 1)
        }.takeWhile { it <= last }.toList()

        val figureGoals = EcoGoals(maxConsumption = BigDecimal.ONE, minEvSharePct = 1)
        val legacyJudged = allMonths.map { ecoMonth(goals, trips, WARSAW, it) }
        val legacyFigures = allMonths.map { ecoMonth(figureGoals, trips, WARSAW, it) }.filter { it.enoughDriving }

        var legacyLowest: EcoRecord? = null
        for (m in legacyFigures) {
            val v = m.consumption?.actual ?: continue
            if (legacyLowest == null || v < legacyLowest.value) legacyLowest = EcoRecord(m.month, v)
        }
        var legacyHighest: EcoRecord? = null
        for (m in legacyFigures) {
            val v = m.ev?.actual ?: continue
            if (legacyHighest == null || v > legacyHighest.value) legacyHighest = EcoRecord(m.month, v)
        }

        fun streakCalc(months: List<EcoMonth>, since: MonthKey?, verdict: (EcoMonth) -> Boolean?): EcoStreakCount {
            var run = 0
            var longest = 0
            for (month in months) {
                if (since != null && month.month < since) continue
                val met = verdict(month) ?: continue
                run = if (met) run + 1 else 0
                longest = maxOf(longest, run)
            }
            return EcoStreakCount(run, longest)
        }

        val legacyHistory = EcoHistory(
            consumptionStreak = streakCalc(legacyJudged, goals.consumptionSince) { it.consumption?.met },
            evStreak = streakCalc(legacyJudged, goals.evSince) { it.ev?.met },
            lowestConsumption = legacyLowest,
            highestEvShare = legacyHighest,
            judgedMonths = legacyFigures.size,
        )

        val actualHistory = ecoHistory(goals, trips, WARSAW, TODAY)
        assertEquals(legacyHistory, actualHistory)
    }
}
