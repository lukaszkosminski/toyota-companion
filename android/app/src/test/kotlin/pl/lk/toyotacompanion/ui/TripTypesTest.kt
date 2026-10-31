package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/** 0.002 degrees of latitude is about 222 m, clearly outside the 150 m of one place. */
private const val HOME_LAT = 54.262
private const val HOME_LON = 18.628
private const val WORK_LAT = 54.367
private const val WORK_LON = 18.612
private const val SHOP_LAT = 54.300
private const val SHOP_LON = 18.700

private val HOME_SPOT = HOME_LAT to HOME_LON
private val WORK_SPOT = WORK_LAT to WORK_LON
private val SHOP_SPOT = SHOP_LAT to SHOP_LON

private fun at(text: String): Instant = Instant.parse(text)

private fun assertBd(expected: String, actual: BigDecimal, label: String) {
    val value = BigDecimal(expected)
    kotlin.test.assertTrue(value.compareTo(actual) == 0, "$label expected $expected but was $actual")
}

/** November 2026: 1st is a Sunday, 2nd to 6th are Monday to Friday, 7th is a Saturday. */
private fun place(lat: Double, lon: Double, stops: Int, totalHours: Long): ParkingPlace =
    ParkingPlace(lat, lon, stops, Duration.ofHours(totalHours), at("2026-11-06T20:00:00Z"))

/** One stop beginning at [from], [minutes] long, at [spot]. */
private fun stop(from: Instant, minutes: Long, spot: Pair<Double, Double>): ParkingStop =
    ParkingStop(from, from.plusSeconds(minutes * 60), spot.first, spot.second)

/**
 * Working-day mornings at [spot]: 06:00 UTC is 07:00 in Warsaw in November, and the car stays
 * [minutes]. [firstDay] is the day of the month, 2 November 2026 being a Monday.
 */
/** Monday to Friday of the first full week of November 2026, one morning per index. */
private fun morning(index: Int): Instant = at("2026-11-0${2 + index}T06:00:00Z")

private fun mornings(
    spot: Pair<Double, Double>,
    count: Int = 5,
    minutes: Long = 480,
): List<ParkingStop> = (0 until count).map { index -> stop(morning(index), minutes, spot) }

private val HOME_PLACE = place(HOME_LAT, HOME_LON, stops = 30, totalHours = 400)
private val WORK_PLACE = place(WORK_LAT, WORK_LON, stops = 20, totalHours = 200)
private val PLACES = KnownPlaces(HOME_PLACE, WORK_PLACE)

/** The latitude of the recognised work place, or the failure the reader should see. */
private fun workOf(log: ParkingLog): Double = requireNotNull(knownPlaces(log, WARSAW).work).lat

/** Types of the given trips, in the zone of the diary. */
private fun typesOf(vararg rows: StoredTrip, places: KnownPlaces = PLACES): Map<String, TripType> =
    classifyTrips(rows.toList(), places, WARSAW)

private fun trip(
    id: String,
    start: String,
    hours: Long = 1,
    from: Pair<Double, Double>? = null,
    to: Pair<Double, Double>? = null,
    km: Double = 10.0,
    price: String? = null,
    priced: Boolean = true,
): StoredTrip {
    val begin = at(start)
    val record = TripRecord(
        trip = Trip(id, begin, begin.plusSeconds(hours * 3_600), km, BigDecimal("1.000"), null),
        vin = "VINAAA111",
        startLat = from?.first,
        startLon = from?.second,
        endLat = to?.first,
        endLon = to?.second,
    )
    val cost = price?.let {
        TripCost(
            tripId = id,
            costPln = BigDecimal(it),
            pricePerLiter = null,
            refuelId = null,
            source = if (priced) CostSource.REFUEL else CostSource.NONE,
        )
    }
    return StoredTrip(record = record, cost = cost)
}

/** docs/SPEC.md §20: the two places the app recognises and what each trip was used for. */
class TripTypesTest {

    @Test
    fun home_needs_five_stops() {
        val four = ParkingLog(emptyList(), listOf(place(HOME_LAT, HOME_LON, 4, 100)), 0, 0)
        val five = ParkingLog(emptyList(), listOf(place(HOME_LAT, HOME_LON, 5, 100)), 0, 0)

        assertNull(knownPlaces(four, WARSAW).home, "four stops are not a home yet")
        val home = requireNotNull(knownPlaces(five, WARSAW).home)
        assertEquals(HOME_LAT, home.lat, "the diary lists places by total time, the first is home")
    }

    @Test
    fun home_is_the_top_place_of_the_diary() {
        val top = place(HOME_LAT, HOME_LON, 5, 10)
        val later = place(WORK_LAT, WORK_LON, 40, 900)
        val log = ParkingLog(mornings(WORK_SPOT), listOf(top, later), 0, 0)

        val places = knownPlaces(log, WARSAW)

        assertEquals(HOME_LAT, requireNotNull(places.home).lat, "the first place of the diary is home")
        assertEquals(WORK_LAT, workOf(log), "the other one is the only candidate")
    }

    @Test
    fun work_needs_five_working_day_mornings() {
        val work = place(WORK_LAT, WORK_LON, 6, 60)
        val places = listOf(HOME_PLACE, work)

        val job = ParkingLog(mornings(WORK_SPOT), places, 0, 0)
        assertEquals(WORK_LAT, workOf(job), "five mornings is a job")

        val four = ParkingLog(mornings(WORK_SPOT, count = 4), places, 0, 0)
        assertNull(knownPlaces(four, WARSAW).work, "four mornings is not a job")
    }

    @Test
    fun a_weekend_or_a_short_or_a_late_morning_does_not_count_as_work() {
        val work = place(WORK_LAT, WORK_LON, 6, 60)
        val saturday = stop(at("2026-11-07T06:00:00Z"), 480, WORK_SPOT)
        val late = stop(at("2026-11-03T10:00:00Z"), 480, WORK_SPOT)
        val short = stop(at("2026-11-04T06:00:00Z"), 239, WORK_SPOT)
        val log = ParkingLog(
            listOf(saturday, late, short) + mornings(WORK_SPOT, count = 2),
            listOf(HOME_PLACE, work),
            0,
            0,
        )

        assertNull(knownPlaces(log, WARSAW).work, "five good mornings are needed, not eight odd ones")

        val fourHours = ParkingLog(mornings(WORK_SPOT, minutes = 240), listOf(HOME_PLACE, work), 0, 0)
        assertEquals(WORK_LAT, workOf(fourHours), "four hours of stay is enough")
    }

    @Test
    fun the_work_morning_is_told_in_the_zone_of_the_diary() {
        val work = place(WORK_LAT, WORK_LON, 6, 60)
        val earlyInUtc = (0 until 5).map { index ->
            stop(at("2026-11-0${2 + index}T04:59:00Z"), 480, WORK_SPOT)
        }
        val atSixLocal = (0 until 5).map { index ->
            stop(at("2026-11-0${2 + index}T05:00:00Z"), 480, WORK_SPOT)
        }

        val beforeSix = ParkingLog(earlyInUtc, listOf(HOME_PLACE, work), 0, 0)
        val fromSix = ParkingLog(atSixLocal, listOf(HOME_PLACE, work), 0, 0)

        assertNull(knownPlaces(beforeSix, WARSAW).work, "05:59 in Warsaw is before the morning")
        assertEquals(WORK_LAT, workOf(fromSix), "06:00 in Warsaw is the first allowed hour")
    }

    @Test
    fun a_tie_between_two_places_goes_to_the_longer_stay() {
        val nearSpot = 54.300 to 18.700
        val farSpot = 54.400 to 18.500
        val near = place(54.300, 18.700, 6, 40)
        val far = place(54.400, 18.500, 6, 90)
        val log = ParkingLog(
            mornings(nearSpot) + mornings(farSpot),
            listOf(HOME_PLACE, near, far),
            0,
            0,
        )

        assertEquals(54.400, workOf(log), "five mornings each, ninety hours over forty")

        val staysLonger = near.copy(total = Duration.ofHours(90))
        val staysShorter = far.copy(total = Duration.ofHours(40))
        val flipped = ParkingLog(
            mornings(nearSpot) + mornings(farSpot),
            listOf(HOME_PLACE, staysLonger, staysShorter),
            0,
            0,
        )
        assertEquals(54.300, workOf(flipped), "now the first one stays longer")
    }

    @Test
    fun a_commute_is_recognised_in_both_directions() {
        val there = trip("there", "2026-11-02T06:00:00Z", from = HOME_SPOT, to = WORK_SPOT)
        val back = trip("back", "2026-11-02T15:00:00Z", from = WORK_SPOT, to = HOME_SPOT)

        val types = classifyTrips(listOf(back, there), PLACES, WARSAW)

        assertEquals(TripType.COMMUTE, types["there"], "home to work")
        assertEquals(TripType.COMMUTE, types["back"], "work to home")
    }

    @Test
    fun a_commute_stays_a_commute_on_a_sunday() {
        val there = trip("there", "2026-11-01T06:00:00Z", from = HOME_SPOT, to = WORK_SPOT)

        assertEquals(TripType.COMMUTE, typesOf(there)["there"], "a commute stays a commute")
    }

    @Test
    fun a_saturday_away_from_home_is_a_weekend_trip() {
        val shopping = trip("shop", "2026-11-07T09:00:00Z", from = HOME_SPOT, to = SHOP_SPOT)

        assertEquals(TripType.WEEKEND, typesOf(shopping)["shop"], "Saturday in Warsaw")
    }

    @Test
    fun the_weekend_is_judged_in_the_zone_of_the_list() {
        // 2026-11-06 23:30 UTC is 00:30 on Saturday the 7th in Warsaw.
        val lateNight = trip("late", "2026-11-06T23:30:00Z", from = HOME_SPOT, to = SHOP_SPOT)
        val friday = trip("friday", "2026-11-06T22:00:00Z", from = HOME_SPOT, to = SHOP_SPOT)

        val types = classifyTrips(listOf(lateNight, friday), PLACES, WARSAW)

        assertEquals(TripType.WEEKEND, types["late"], "after midnight in Warsaw it is already Saturday")
        assertEquals(TripType.ERRAND, types["friday"], "Friday evening, the car stood for half an hour")
    }

    @Test
    fun a_stop_under_two_hours_after_a_trip_is_an_errand() {
        val out = trip("out", "2026-11-02T09:00:00Z", from = HOME_SPOT, to = SHOP_SPOT)
        val backSoon = trip("back", "2026-11-02T11:59:00Z", from = SHOP_SPOT, to = HOME_SPOT)

        val types = classifyTrips(listOf(out, backSoon), PLACES, WARSAW)

        assertEquals(TripType.ERRAND, types["out"], "one hour and fifty nine minutes of stop")
        assertEquals(TripType.OTHER, types["back"], "it came home, so this stop is not an errand")
    }

    @Test
    fun a_stop_of_two_hours_is_not_an_errand_anymore() {
        val out = trip("out", "2026-11-02T09:00:00Z", from = HOME_SPOT, to = SHOP_SPOT)
        val backLate = trip("back", "2026-11-02T12:00:00Z", from = SHOP_SPOT, to = HOME_SPOT)

        val types = typesOf(out, backLate)

        assertEquals(TripType.OTHER, types["out"], "two hours is a visit, not an errand")
    }

    @Test
    fun a_next_trip_that_starts_before_this_one_ends_is_a_short_stop() {
        // The archive can hold a trip that begins before the previous one has ended. The rule
        // says "less than two hours after the end", and a gap before the end is that.
        val out = trip("out", "2026-11-02T09:00:00Z", hours = 3, from = HOME_SPOT, to = SHOP_SPOT)
        val back = trip("back", "2026-11-02T11:00:00Z", from = SHOP_SPOT, to = HOME_SPOT)

        val types = classifyTrips(listOf(out, back), PLACES, WARSAW)

        assertEquals(TripType.ERRAND, types["out"], "the gap is read as it is, also when negative")
    }

    @Test
    fun the_last_trip_of_the_archive_has_no_stop_and_is_other() {
        val there = trip("there", "2026-11-02T06:00:00Z", from = HOME_SPOT, to = SHOP_SPOT)
        val further = trip("further", "2026-11-02T08:30:00Z", from = SHOP_SPOT, to = 54.400 to 19.000)

        val types = classifyTrips(listOf(there, further), PLACES, WARSAW)

        assertEquals(TripType.ERRAND, types["there"], "it stood there for an hour and a half")
        assertEquals(TripType.OTHER, types["further"], "no trip after it, so its stop is unknown")
    }

    @Test
    fun without_home_or_work_nothing_is_a_commute() {
        val there = trip("there", "2026-11-02T06:00:00Z", from = HOME_SPOT, to = WORK_SPOT)

        assertEquals(
            TripType.OTHER,
            classifyTrips(listOf(there), KnownPlaces(null, null), WARSAW)["there"],
            "no places, no commute",
        )
        assertEquals(
            TripType.OTHER,
            classifyTrips(listOf(there), KnownPlaces(HOME_PLACE, null), WARSAW)["there"],
            "a home alone is not a commute",
        )
        assertEquals(
            TripType.OTHER,
            classifyTrips(listOf(there), KnownPlaces(null, WORK_PLACE), WARSAW)["there"],
            "a work alone is not a commute",
        )
    }

    @Test
    fun a_missing_or_broken_position_is_near_nothing() {
        val blind = trip("blind", "2026-11-02T06:00:00Z")
        val notANumber = Double.NaN to Double.NaN
        val broken = trip("broken", "2026-11-02T12:00:00Z", from = notANumber, to = notANumber)

        val types = classifyTrips(listOf(blind, broken), PLACES, WARSAW)

        assertEquals(TripType.OTHER, types["blind"], "a trip without GPS is not a commute")
        assertEquals(TripType.OTHER, types["broken"], "NaN coordinates are near nothing")

        val noon = at("2026-11-03T06:00:00Z")
        val positionless = ParkingStop(noon, noon.plusSeconds(8 * 3_600), null, null)
        val stops = mornings(WORK_SPOT, count = 4) + positionless
        val log = ParkingLog(stops, listOf(HOME_PLACE, WORK_PLACE), 0, 0)
        assertNull(knownPlaces(log, WARSAW).work, "a stop without a position cannot belong to a place")
    }

    @Test
    fun summaries_come_in_the_fixed_order_and_empty_types_are_left_out() {
        val trips = listOf(
            trip("c1", "2026-11-02T06:00:00Z", km = 10.0),
            trip("c2", "2026-11-03T06:00:00Z", km = 12.45),
            trip("e1", "2026-11-04T09:00:00Z", km = 5.0),
            trip("o1", "2026-11-05T09:00:00Z", km = 7.0),
        )
        val types = mapOf(
            "c1" to TripType.COMMUTE,
            "c2" to TripType.COMMUTE,
            "e1" to TripType.ERRAND,
            "o1" to TripType.OTHER,
        )

        val rows = tripTypeSummaries(trips, types)

        assertEquals(
            listOf(TripType.COMMUTE, TripType.ERRAND, TripType.OTHER),
            rows.map { it.type },
            "no weekend trip, so no weekend row",
        )
        assertEquals(listOf(2, 1, 1), rows.map { it.trips }, "how many of each")
        assertBd("22.5", rows.first().distanceKm, "22,44 km rounded up to a tenth")
        assertEquals(listOf(null, null, null), rows.map { it.costPln }, "no price anywhere")
    }

    @Test
    fun a_summary_adds_only_the_trips_that_have_a_price() {
        val trips = listOf(
            trip("c1", "2026-11-02T06:00:00Z", price = "12.34"),
            trip("c2", "2026-11-03T06:00:00Z", price = "0.005"),
            trip("c3", "2026-11-04T06:00:00Z", price = "99.00", priced = false),
        )
        val types = mapOf(
            "c1" to TripType.COMMUTE,
            "c2" to TripType.COMMUTE,
            "c3" to TripType.COMMUTE,
        )

        val row = tripTypeSummaries(trips, types).single()

        assertEquals(3, row.trips, "all three are commutes")
        assertBd("12.35", row.costPln!!, "the trip without a price adds nothing, half up on the grosz")
    }

    @Test
    fun a_summary_counts_a_trip_whose_distance_is_broken() {
        val trips = listOf(
            trip("c1", "2026-11-02T06:00:00Z", km = 10.0),
            trip("c2", "2026-11-03T06:00:00Z", km = Double.NaN),
        )
        val types = mapOf("c1" to TripType.COMMUTE, "c2" to TripType.COMMUTE)

        val row = tripTypeSummaries(trips, types).single()

        assertEquals(2, row.trips, "the trip is still a trip")
        assertBd("10.0", row.distanceKm, "but it adds no kilometres")
    }
}
