package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private const val VIN = "VINAAA111"

private val T0: Instant = Instant.parse("2026-06-01T08:00:00Z")

/** Minutes after [T0]; parking rules talk in gaps, not in epochs. */
private fun at(minutes: Long): Instant = T0.plusSeconds(minutes * 60)

/** Home and work are kilometres apart, so a test can never mix them up by accident. */
private const val HOME_LAT = 54.350
private const val HOME_LON = 18.600
private const val WORK_LAT = 54.400
private const val WORK_LON = 18.700

/** A leg along one meridian: it starts at `from` and ends at `to`, both on [HOME_LON]. */
private fun leg(id: String, start: Instant, from: Double, to: Double): TripRecord =
    trip(id, start, startLat = from, startLon = HOME_LON, endLat = to, endLon = HOME_LON)

/**
 * The diary is a pure function of the archived trips (docs/PRD.md F17), so the trips are built
 * here without any repository or gateway: no Toyota call is allowed to learn where the car is
 * (AGENTS.md forbids `refresh_status()` for this).
 */
private fun trip(
    id: String,
    start: Instant,
    driveMinutes: Long = 30,
    startLat: Double? = null,
    startLon: Double? = null,
    endLat: Double? = null,
    endLon: Double? = null,
): TripRecord = TripRecord(
    trip = Trip(
        id = id,
        start = start,
        end = start.plusSeconds(driveMinutes * 60),
        distanceKm = 8.0,
        fuelL = BigDecimal("0.800"),
        evDistanceKm = null,
    ),
    vin = VIN,
    startLat = startLat,
    startLon = startLon,
    endLat = endLat,
    endLon = endLon,
)

/** A trip that begins and ends at the same position, so the gap around it is one place. */
private fun tripAt(
    id: String,
    start: Instant,
    lat: Double,
    lon: Double,
    driveMinutes: Long = 30,
): TripRecord =
    trip(id, start, driveMinutes, startLat = lat, startLon = lon, endLat = lat, endLon = lon)

private fun closeEnough(expected: Double, actual: Double, tolerance: Double, message: String) {
    val ok = abs(expected - actual) <= tolerance
    assertTrue(ok, "$message expected $expected +/- $tolerance got $actual")
}

/** docs/PRD.md F17: the car stood where one trip ended and the next began. */
class ParkingStopsTest {
    @Test
    fun a_stop_is_recorded_when_the_next_trip_starts_within_150_metres() {
        // 0.00134 degrees of latitude is about 149 m, so this is the same place.
        val first = trip("a", at(0), endLat = HOME_LAT, endLon = HOME_LON)
        val next = trip("b", at(60), startLat = HOME_LAT + 0.00134, startLon = HOME_LON)

        val log = parkingLog(listOf(first, next))

        assertEquals(0, log.unmatched, "a matched pair is not an unmatched gap")
        assertEquals(0, log.longGaps, "an hour is not a long gap")
        assertEquals(1, log.stops.size, "one gap of an hour is one stop")
        val stop = log.stops.single()
        assertEquals(at(30), stop.from, "the stop begins when the first trip ends")
        assertEquals(at(60), stop.to, "the stop ends when the next trip starts")
        assertEquals(Duration.ofMinutes(30), stop.duration, "the stop lasts half an hour")
        val latitude = requireNotNull(stop.lat) { "a matched pair has a position" }
        closeEnough(HOME_LAT + 0.00067, latitude, 1e-9, "the position is the middle of the two ends")
        closeEnough(HOME_LON, requireNotNull(stop.lon), 1e-9, "the longitude is the middle too")
        // The screen only ever shows the rounded form (docs/SECURITY.md).
        assertEquals("54.351", roundedCoordinate(latitude), "the shown coordinate is coarse to 100 m")
    }

    @Test
    fun a_next_trip_further_than_150_metres_is_an_unmatched_gap_and_not_a_stop() {
        // 0.001358 degrees is about 151 m: the car moved without an archived trip.
        val first = trip("a", at(0), endLat = HOME_LAT, endLon = HOME_LON)
        val next = trip("b", at(60), startLat = HOME_LAT + 0.001358, startLon = HOME_LON)

        val log = parkingLog(listOf(first, next))

        assertEquals(emptyList(), log.stops, "an unknown position is not a stop")
        assertEquals(emptyList(), log.places, "a place needs a position")
        assertEquals(1, log.unmatched, "the gap is counted as a missing trip")
        assertEquals(0, log.longGaps, "an hour is not a long gap")
    }

    @Test
    fun a_gap_of_exactly_thirty_minutes_is_a_stop() {
        val first = trip("a", at(0))
        val next = trip("b", at(60))

        val log = parkingLog(listOf(first, next))

        assertEquals(1, log.stops.size, "thirty minutes is the minimum, inclusive")
        assertEquals(Duration.ofMinutes(30), log.stops.single().duration, "the stop is thirty minutes")
    }

    @Test
    fun a_gap_of_twenty_nine_minutes_and_fifty_nine_seconds_is_no_stop() {
        val first = trip("a", at(0))
        val next = trip("b", at(30).plusSeconds(29 * 60 + 59))

        val log = parkingLog(listOf(first, next))

        assertEquals(emptyList(), log.stops, "a stopover in a journey is not a parked car")
        assertEquals(0, log.unmatched, "a too short gap is not a missing trip either")
        assertEquals(0, log.longGaps, "a too short gap is not a long gap")
    }

    @Test
    fun a_gap_of_exactly_twenty_one_days_is_still_a_stop() {
        val first = trip("a", at(0))
        val next = trip("b", at(30).plusSeconds(21L * 86_400))

        val log = parkingLog(listOf(first, next))

        assertEquals(1, log.stops.size, "the limit is exclusive")
        assertEquals(0, log.longGaps, "twenty one days is not over the limit")
        assertEquals(Duration.ofDays(21), log.stops.single().duration, "the stop lasts three weeks")
    }

    @Test
    fun a_gap_of_twenty_one_days_and_one_second_is_a_long_gap() {
        val first = trip("a", at(0))
        val next = trip("b", at(30).plusSeconds(21L * 86_400 + 1))

        val log = parkingLog(listOf(first, next))

        assertEquals(emptyList(), log.stops, "a hole in the archive is not a place")
        assertEquals(1, log.longGaps, "the hole is counted instead")
        assertEquals(0, log.unmatched, "a long gap is not a missing trip")
    }

    @Test
    fun a_stop_without_coordinates_is_kept_without_a_position() {
        // Trips mapped without a summary keep no GPS; the diary must not lose the time.
        val first = trip("a", at(0))
        val next = trip("b", at(120))

        val log = parkingLog(listOf(first, next))

        assertEquals(1, log.stops.size, "a stop we cannot place is still a stop")
        val stop = log.stops.single()
        assertEquals(null, stop.lat, "no coordinates means no latitude")
        assertEquals(null, stop.lon, "no coordinates means no longitude")
        assertEquals(0, log.unmatched, "missing GPS is not an unmatched pair")
        assertEquals(emptyList(), log.places, "places are only the stops we can put on a map")
    }

    @Test
    fun overlapping_trips_leave_no_stop_and_no_counter() {
        // The next trip began before the previous one ended (a bad payload): there is no gap.
        val first = trip("a", at(0))
        val overlap = trip("b", at(20))

        val log = parkingLog(listOf(first, overlap))

        assertEquals(emptyList(), log.stops, "a negative gap is no parking time")
        assertEquals(0, log.unmatched, "a negative gap is not a missing trip")
        assertEquals(0, log.longGaps, "a negative gap is not a long gap")
    }

    @Test
    fun the_stops_begin_at_strictly_earlier_moments_from_newest_to_oldest() {
        val trips = listOf(
            tripAt("h1", at(0), HOME_LAT, HOME_LON),
            tripAt("h2", at(60), HOME_LAT, HOME_LON),
            tripAt("h3", at(180), HOME_LAT, HOME_LON),
            tripAt("h4", at(300), HOME_LAT, HOME_LON),
            tripAt("h5", at(330), WORK_LAT, WORK_LON),
        )

        val begins = parkingLog(trips).stops.map { it.from }

        assertEquals(begins.sortedDescending(), begins, "the diary is newest first without repeats")
        assertEquals(begins.size, begins.distinct().size, "no two stops begin at the same moment")
    }

    @Test
    fun one_trip_alone_and_no_trip_at_all_have_no_stops() {
        val empty = ParkingLog(stops = emptyList(), places = emptyList(), unmatched = 0, longGaps = 0)

        assertEquals(empty, parkingLog(emptyList()), "an empty archive has no parking diary")
        assertEquals(empty, parkingLog(listOf(trip("a", at(0)))), "one trip leaves no gap")
    }

    @Test
    fun the_order_of_the_input_does_not_change_the_diary() {
        val trips = homeChain()

        val ordered = parkingLog(trips)
        val backwards = parkingLog(trips.reversed())
        val mixed = parkingLog(listOf(trips[2], trips[0], trips[3], trips[1]))

        assertEquals(3, ordered.stops.size, "the chain has three gaps between four trips")
        assertEquals(ordered, backwards, "the diary does not depend on the input order")
        assertEquals(ordered, mixed, "nor on how the rows arrive from the read-model")
    }

    @Test
    fun three_stops_at_the_same_place_are_one_place() {
        val log = parkingLog(homeChain())

        assertEquals(1, log.places.size, "the same place three times is one place")
        val place = log.places.single()
        assertEquals(3, place.stops, "all three stops belong to home")
        assertEquals(Duration.ofMinutes(210), place.total, "30 min + 1 h 30 + 1 h 30")
        assertEquals(at(210), place.lastStop, "the newest stop is the third one")
        closeEnough(HOME_LAT, place.lat, 1e-9, "the centre is the position of the first stop")
        // The list itself is newest first, so the user reads the last parked car first.
        assertEquals(listOf(at(210), at(90), at(30)), log.stops.map { it.from }, "newest stop first")
    }

    @Test
    fun the_centre_of_a_place_is_its_first_stop_and_not_the_average() {
        val next = HOME_LAT + 0.0005
        val further = HOME_LAT + 0.001
        val trips = listOf(
            leg("a", at(0), HOME_LAT, HOME_LAT),
            leg("b", at(60), HOME_LAT, next),
            leg("c", at(150), next, further),
            leg("d", at(240), further, further),
        )

        val place = parkingLog(trips).places.single()

        assertEquals(3, place.stops, "0 m, 55 m and 111 m from the first stop are one car park")
        assertEquals(Duration.ofMinutes(150), place.total, "30 min + 60 min + 60 min")
        closeEnough(HOME_LAT, place.lat, 1e-9, "the centre stays where the car was first seen")
    }

    @Test
    fun a_stop_between_two_places_joins_the_one_seen_first() {
        val work = HOME_LAT + 0.0018
        val between = HOME_LAT + 0.0009
        val trips = listOf(
            leg("a", at(0), HOME_LAT, HOME_LAT),
            leg("b", at(60), HOME_LAT, work),
            leg("c", at(150), work, between),
            leg("d", at(240), between, between),
        )

        val log = parkingLog(trips)

        val nearBoth = distanceMeters(between, HOME_LON, work, HOME_LON) <= PARKING_MATCH_M
        assertTrue(nearBoth, "the third stop lies within the limit of both places")
        assertEquals(2, log.places.size, "two places keep two centres")
        assertEquals(listOf(2, 1), log.places.map { it.stops }, "it joined the place seen first")
        closeEnough(HOME_LAT, log.places.first().lat, 1e-9, "the first place is the one opened first")
    }

    @Test
    fun a_stop_needs_coordinates_on_both_ends_to_have_a_position() {
        val log = parkingLog(
            listOf(trip("a", at(0), endLat = HOME_LAT, endLon = HOME_LON), trip("b", at(60))),
        )

        assertEquals(1, log.stops.size, "a gap without a position is still a stop")
        val stop = log.stops.single()
        assertEquals(null, stop.lat, "one end without a summary leaves the place unknown")
        assertEquals(null, stop.lon, "and that includes the longitude")
        assertEquals(0, log.unmatched, "a missing coordinate is not a missing trip")

        val mirrored = parkingLog(
            listOf(trip("a", at(0)), trip("b", at(60), startLat = HOME_LAT, startLon = HOME_LON)),
        )
        assertEquals(1, mirrored.stops.size, "the other way round it is the same")
        assertEquals(null, mirrored.stops.single().lat, "still no position")
    }

    @Test
    fun places_are_ordered_by_total_time_and_the_newer_place_wins_a_tie() {
        val homeAndWork = listOf(
            tripAt("h1", at(0), HOME_LAT, HOME_LON),
            tripAt("h2", at(90), HOME_LAT, HOME_LON),
            tripAt("h3", at(240), HOME_LAT, HOME_LON),
            tripAt("w1", at(360), WORK_LAT, WORK_LON),
            tripAt("w2", at(630), WORK_LAT, WORK_LON),
        )

        val log = parkingLog(homeAndWork)

        assertEquals(2, log.places.size, "home and work are two places")
        assertEquals(listOf(WORK_LAT, HOME_LAT), log.places.map { it.lat }, "work beats home by total")
        assertEquals(Duration.ofHours(4), log.places.first().total, "work has one stop of four hours")
        assertEquals(2, log.places[1].stops, "home has two stops")
        assertEquals(Duration.ofHours(3), log.places[1].total, "home totals three hours")
        assertEquals(1, log.unmatched, "going from home to work is a missing trip in between")
    }

    @Test
    fun a_tie_in_total_time_is_broken_by_the_newest_stop() {
        val equalTotals = listOf(
            tripAt("h1", at(0), HOME_LAT, HOME_LON),
            tripAt("h2", at(90), HOME_LAT, HOME_LON),
            tripAt("w1", at(180), WORK_LAT, WORK_LON),
            tripAt("w2", at(270), WORK_LAT, WORK_LON),
        )

        val log = parkingLog(equalTotals)

        val oneHour = Duration.ofHours(1)
        assertEquals(listOf(oneHour, oneHour), log.places.map { it.total }, "both total one hour")
        assertEquals(listOf(WORK_LAT, HOME_LAT), log.places.map { it.lat }, "newest place first")
    }

    @Test
    fun distance_meters_matches_known_values() {
        // One minutes of arc: 0.001 degrees of latitude is 111,19 m wherever we are.
        closeEnough(111.19, distanceMeters(54.3, 18.6, 54.301, 18.6), 1.0, "0.001 degrees of latitude")
        closeEnough(111.19, distanceMeters(0.0, 0.0, 0.001, 0.0), 1.0, "latitude is place independent")
        // Longitude closes with the cosine of the latitude: at 60 degrees north it is half.
        closeEnough(55.6, distanceMeters(60.0, 18.0, 60.0, 18.001), 1.0, "lon at 60 N is half of lat")
        assertEquals(0.0, distanceMeters(54.3, 18.6, 54.3, 18.6), "a point is zero metres from itself")
    }

    @Test
    fun rounded_coordinate_shows_three_decimals_with_a_dot() {
        assertEquals("54.372", roundedCoordinate(54.37249), "a fourth decimal below five rounds down")
        assertEquals("54.373", roundedCoordinate(54.3725), "exactly a half rounds up (HALF_UP)")
        assertEquals("-0.001", roundedCoordinate(-0.0005), "a negative half rounds away from zero")
        assertEquals("54.400", roundedCoordinate(54.4), "a short value is padded to three decimals")
        assertEquals("0.000", roundedCoordinate(0.00004), "a tiny value is zero, not a sign")
    }
}

/** Four trips at home with gaps of 30 min, 1 h 30 and 1 h 30. */
private fun homeChain(): List<TripRecord> = listOf(
    tripAt("h1", at(0), HOME_LAT, HOME_LON),
    tripAt("h2", at(60), HOME_LAT, HOME_LON),
    tripAt("h3", at(180), HOME_LAT, HOME_LON),
    tripAt("h4", at(300), HOME_LAT, HOME_LON),
)
