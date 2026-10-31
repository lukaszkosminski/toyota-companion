package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
private val T0: Instant = Instant.parse("2026-10-01T08:00:00Z")

private fun at(minutes: Long): Instant = T0.plusSeconds(minutes * 60)

private fun assertBd(expected: String, actual: BigDecimal, label: String) {
    val value = BigDecimal(expected)
    assertTrue(value.compareTo(actual) == 0, "$label expected $expected but was $actual")
}

/**
 * The eco score and the anomalies are pure functions of the archive (PRD F12, F13), so the
 * rows are built here: no repository, no gateway, and no position ever leaves a group
 * (docs/SECURITY.md, ADR-008).
 */
private fun stored(
    id: String,
    start: Instant,
    km: Double = 10.0,
    fuel: String = "0.800",
    score: Int? = 90,
): StoredTrip = StoredTrip(
    record = TripRecord(
        trip = Trip(
            id = id,
            start = start,
            end = start.plusSeconds(1_800),
            distanceKm = km,
            fuelL = BigDecimal(fuel),
            evDistanceKm = null,
        ),
        vin = "VINAAA111",
        scoreGlobal = score,
    ),
    cost = null,
)

/** One drive, [hour] hours after the start of the archive. */
private fun drive(tag: String, fuel: String, hour: Int, km: Double = 10.0): StoredTrip =
    stored(tag, at(hour * 60L), km = km, fuel = fuel)

/** [count] drives an hour apart, ids suffixed with their number, oldest first. */
private fun same(
    tag: String,
    count: Int,
    fuel: String,
    hour: Int = 0,
    km: Double = 10.0,
): List<StoredTrip> = (0 until count).map { index -> drive("$tag$index", fuel, hour + index, km) }

/** A route of drives on the standard 10 km, one hour apart, oldest first. */
private fun route(vararg rows: Pair<String, String>): RouteGroup =
    RouteGroup(rows.mapIndexed { hour, (tag, fuel) -> drive(tag, fuel, hour) })

/** docs/SPEC.md §19: the eco score over months, and the drives that drank more than usual. */
class DrivingStyleTest {

    @Test
    fun the_monthly_score_is_weighted_by_the_distance() {
        val trips = listOf(
            stored("a", at(0), km = 10.0, score = 90),
            stored("b", at(60), km = 40.0, score = 60),
        )

        val month = ecoScoreByMonth(trips, WARSAW).single()

        assertEquals(MonthKey(2026, 10), month.month, "the month of the trip start")
        assertBd("66.0", month.score, "90 km-points over 50 km")
        assertEquals(2, month.trips, "both trips have a score")
    }

    @Test
    fun a_trip_without_a_score_is_left_out_of_the_average_and_the_count() {
        val trips = listOf(
            stored("a", at(0), km = 10.0, score = 90),
            stored("b", at(60), km = 40.0, score = 60),
            stored("c", at(120), km = 500.0, score = null),
        )

        val month = ecoScoreByMonth(trips, WARSAW).single()

        assertBd("66.0", month.score, "the long trip without a score carries no weight")
        assertEquals(2, month.trips, "only the scored trips count")
    }

    @Test
    fun the_score_rounds_to_one_tenth_of_a_point() {
        val trips = listOf(
            stored("a", at(0), km = 2.0, score = 90),
            stored("b", at(60), km = 1.0, score = 91),
        )

        assertBd("90.3", ecoScoreByMonth(trips, WARSAW).single().score, "271 points over 3 km")
    }

    @Test
    fun the_month_is_the_local_month_of_the_trip_start() {
        // 2026-03-31 23:30 UTC is already 00:30 on 1 April in Warsaw.
        val late = stored("a", Instant.parse("2026-03-31T23:30:00Z"))

        val month = ecoScoreByMonth(listOf(late), WARSAW).single().month
        assertEquals(MonthKey(2026, 4), month, "the zone of the list decides the month")
    }

    @Test
    fun months_come_newest_first_and_a_month_without_a_score_is_absent() {
        val trips = listOf(
            stored("a", Instant.parse("2025-11-05T08:00:00Z"), score = 80),
            stored("b", Instant.parse("2026-01-05T08:00:00Z"), score = 70),
            stored("c", Instant.parse("2025-12-05T08:00:00Z"), score = null),
        )

        val months = ecoScoreByMonth(trips, WARSAW).map { it.month }

        val expected = listOf(MonthKey(2026, 1), MonthKey(2025, 11))
        assertEquals(expected, months, "newest first, no empty months")
    }

    @Test
    fun a_short_or_fuelless_drive_has_no_consumption() {
        val short = stored("a", at(0), km = 1.9, fuel = "0.800")
        val empty = stored("b", at(0), km = 2.0, fuel = "0.000")
        val negative = stored("c", at(0), km = 2.0, fuel = "-0.500")
        val broken = stored("d", at(0), km = Double.NaN, fuel = "0.800")

        assertEquals(null, routeConsumption(short), "under two kilometres")
        assertEquals(null, routeConsumption(empty), "no fuel burned")
        assertEquals(null, routeConsumption(negative), "no negative fuel")
        assertEquals(null, routeConsumption(broken), "a distance that is not a number")

        val atTheLimit = requireNotNull(routeConsumption(stored("e", at(0), km = 2.0, fuel = "0.200")))
        assertBd("10.00", atTheLimit, "two kilometres is the first distance that counts")
    }

    @Test
    fun the_median_of_an_odd_number_of_drives_is_the_middle_one() {
        val group = route(
            "a" to "0.400", "b" to "0.600", "c" to "0.800", "d" to "1.000", "e" to "1.200",
        )

        val usual = requireNotNull(medianConsumption(group))
        assertBd("8.00", usual, "of 4, 6, 8, 10, 12 the middle is 8")
    }

    @Test
    fun the_median_of_an_even_number_is_the_mean_of_the_two_middle() {
        val group = route("a" to "0.400", "b" to "0.600", "c" to "0.800", "d" to "1.000")

        val usual = requireNotNull(medianConsumption(group))
        assertBd("7.00", usual, "the mean of 6 and 8")
    }

    @Test
    fun the_median_ignores_the_drives_without_consumption() {
        val hops = same("k", 2, "5.000", hour = 5, km = 1.0)
        val group = RouteGroup(route("a" to "0.400", "b" to "0.600", "c" to "0.800").trips + hops)

        val usual = requireNotNull(medianConsumption(group))
        assertBd("6.00", usual, "of 4, 6, 8 - the two one kilometre hops count for nothing")
    }

    @Test
    fun a_route_with_four_usable_drives_is_not_compared() {
        val usable = same("a", 3, "0.400") + listOf(drive("b", "2.000", 3))
        val drives = usable + same("k", 2, "5.000", 8, km = 1.0)

        assertEquals(emptyList(), fuelAnomalies(listOf(RouteGroup(drives))), "five is the minimum")
    }

    @Test
    fun a_drive_a_quarter_over_the_usual_value_is_an_anomaly() {
        val group = route(
            "a" to "0.400", "b" to "0.600", "c" to "0.800",
            "c2" to "0.800", "c3" to "0.800", "e" to "1.000",
        )

        val anomaly = fuelAnomalies(listOf(group)).single()

        assertBd("10.00", anomaly.lPer100Km, "the drive that drank")
        assertBd("8.00", anomaly.usualLPer100Km, "the usual value of the route")
        assertBd("25.0", anomaly.excessPct, "exactly the threshold counts")
        assertEquals(0, anomaly.routeIndex, "the only group is number zero")
        assertEquals("e", anomaly.trip.record.trip.id, "the newest drive is the one that drank")
    }

    @Test
    fun a_drive_just_below_the_threshold_is_not_an_anomaly() {
        val group = route(
            "a" to "0.400", "b" to "0.600", "c" to "0.800",
            "c2" to "0.800", "c3" to "0.800", "d" to "0.999",
        )

        assertEquals(emptyList(), fuelAnomalies(listOf(group)), "24,9 per cent is not enough")
    }

    @Test
    fun a_route_whose_usual_value_is_zero_is_left_alone() {
        // A hybrid that drove the route on electricity: the percent over zero has no answer.
        val group = RouteGroup(same("a", 5, "0.0004") + listOf(drive("b", "0.001", 5)))

        assertBd("0.00", requireNotNull(medianConsumption(group)), "the usual value is zero")
        assertEquals(emptyList(), fuelAnomalies(listOf(group)), "no anomaly and no division by zero")
    }

    @Test
    fun the_route_index_is_the_place_of_the_group_in_the_input() {
        val quiet = RouteGroup(same("q", 6, "0.400"))
        val noisy = RouteGroup(same("n", 5, "0.400") + listOf(drive("o", "1.000", 5)))

        val anomalies = fuelAnomalies(listOf(quiet, noisy))

        assertEquals(1, anomalies.size, "the quiet route has nothing to report")
        assertEquals(1, anomalies.single().routeIndex, "the second group is number one")
    }

    @Test
    fun anomalies_are_newest_first_and_cut_at_the_list_limit() {
        val quiet = same("q", 40, "0.400")
        val noisy = (0 until 25).map { index ->
            stored("n" + index.toString().padStart(2, '0'), at(1_000L + index * 60L), fuel = "1.000")
        }

        val anomalies = fuelAnomalies(listOf(RouteGroup(quiet + noisy)))
        val ids = anomalies.map { it.trip.record.trip.id }

        assertEquals(ANOMALY_LIMIT, anomalies.size, "25 are found, 20 are shown")
        assertEquals("n24", ids.first(), "the newest drive first")
        assertEquals("n05", ids.last(), "the oldest of the twenty shown")
        assertEquals(ANOMALY_LIMIT, ids.distinct().size, "each anomaly is reported once")
        val hundredFifty = BigDecimal("150.0")
        assertTrue(anomalies.all { it.excessPct.compareTo(hundredFifty) == 0 }, "ten over eight")
    }
}
