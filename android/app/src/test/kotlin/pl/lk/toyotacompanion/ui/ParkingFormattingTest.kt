package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/** Both sides of the en dash the screen puts between the two ends of a stop. */
private const val DASH = " \u2013 "

private const val PARK_LAT = 54.372
private const val PARK_LON = 18.638

/** Late October 2026, so Warsaw is already UTC+1 and the expected text is plain arithmetic. */
private val FROM: Instant = Instant.parse("2026-10-29T13:05:00Z")
private val TO: Instant = Instant.parse("2026-10-30T06:40:00Z")

private fun stopOf(lat: Double?, lon: Double?): ParkingStop =
    ParkingStop(from = FROM, to = TO, lat = lat, lon = lon)

/** A stored trip with the coordinates the mapper kept (docs/SPEC.md §18 reads exactly those). */
private fun storedTrip(
    id: String,
    start: Instant,
    driveMinutes: Long,
    startLat: Double?,
    startLon: Double?,
    endLat: Double?,
    endLon: Double?,
): StoredTrip = StoredTrip(
    record = TripRecord(
        trip = Trip(
            id = id,
            start = start,
            end = start.plusSeconds(driveMinutes * 60),
            distanceKm = 9.0,
            fuelL = BigDecimal("0.700"),
            evDistanceKm = null,
        ),
        vin = "VINAAA111",
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
    ),
    cost = null,
)

/** docs/SPEC.md §18: the Polish text of "Postoje" and the read that builds the diary for it. */
class ParkingFormattingTest {

    @Test
    fun durations_are_shown_in_whole_minutes_rounded_down() {
        assertEquals("2 d 3 h", ParkingFormatting.duration(Duration.ofHours(51)))
        assertEquals("3 h 05 min", ParkingFormatting.duration(Duration.ofMinutes(185)))
        assertEquals("45 min", ParkingFormatting.duration(Duration.ofMinutes(45)))
        assertEquals("0 min", ParkingFormatting.duration(Duration.ofSeconds(59)), "not a minute yet")
        assertEquals("0 min", ParkingFormatting.duration(Duration.ofMinutes(-5)), "never negative")
    }

    @Test
    fun a_position_is_shown_with_three_decimals_and_a_dot() {
        assertEquals("54.372, 18.638", ParkingFormatting.place(PARK_LAT, PARK_LON))
        assertEquals(Texts.PARKING_NO_POSITION, ParkingFormatting.place(null, PARK_LON))
        assertEquals(Texts.PARKING_NO_POSITION, ParkingFormatting.place(PARK_LAT, null))
        assertEquals(Texts.PARKING_NO_POSITION, ParkingFormatting.place(null, null))
    }

    @Test
    fun a_position_never_shows_more_than_about_a_hundred_metres() {
        // An archive coordinate carries the full precision; the screen must not repeat it.
        val fine = ParkingFormatting.place(54.37249183746291, 18.63810294736281)
        val line = ParkingFormatting.stopLine(stopOf(54.37249183746291, 18.63810294736281), WARSAW)
        assertEquals("54.372, 18.638", fine)
        val leaks = Regex("\\d[.,]\\d{4,}").containsMatchIn(line)
        assertFalse(leaks, "the stop line leaks precision: $line")
    }

    @Test
    fun a_stop_line_shows_both_ends_and_the_parked_time() {
        val line = ParkingFormatting.stopLine(stopOf(PARK_LAT, PARK_LON), WARSAW)
        assertEquals("29.10 14:05" + DASH + "30.10 07:40" + Texts.SEPARATOR + "17 h 35 min", line)
    }

    @Test
    fun a_place_line_counts_the_stops_the_total_and_the_last_visit() {
        val place = ParkingPlace(
            lat = PARK_LAT,
            lon = PARK_LON,
            stops = 12,
            total = Duration.ofDays(3).plusHours(4),
            lastStop = FROM,
        )
        assertEquals(
            Texts.parkingStops(12) + Texts.SEPARATOR + Texts.parkingTotal("3 d 4 h") +
                Texts.SEPARATOR + Texts.parkingLast("29.10 14:05"),
            ParkingFormatting.placeLine(place, WARSAW),
        )
    }

    @Test
    fun the_parking_text_does_not_depend_on_the_default_locale() {
        val previous = Locale.getDefault()
        try {
            val locales = listOf(
                Locale.ROOT, Locale.US, Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("ar"),
            )
            for (locale in locales) {
                Locale.setDefault(locale)
                val three = ParkingFormatting.duration(Duration.ofMinutes(185))
                assertEquals("3 h 05 min", three, locale.toString())
                val twoDays = ParkingFormatting.duration(Duration.ofHours(51))
                assertEquals("2 d 3 h", twoDays, locale.toString())
                val place = ParkingFormatting.place(PARK_LAT, PARK_LON)
                assertEquals("54.372, 18.638", place, locale.toString())
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun parkingLogOf_builds_the_diary_from_the_stored_trips() {
        val stored = listOf(
            storedTrip("a", FROM, 30, PARK_LAT, PARK_LON, PARK_LAT, PARK_LON),
            storedTrip("b", TO, 30, PARK_LAT, PARK_LON, PARK_LAT, PARK_LON),
        )

        val log = parkingLogOf(stored)

        assertEquals(1, log.stops.size, "one gap between two trips is one stop")
        assertEquals(1, log.places.size, "that stop belongs to one place")
        assertEquals(0, log.unmatched, "the car did not move between the trips")
        assertEquals(0, log.longGaps, "the gap is not a hole in the archive")
        // Exactly what the two lines of the screen will show for this diary.
        val expected = "29.10 14:35" + DASH + "30.10 07:40" + Texts.SEPARATOR + "17 h 05 min"
        val stop = log.stops.single()
        assertEquals(expected, ParkingFormatting.stopLine(stop, WARSAW))
        val shown = ParkingFormatting.place(stop.lat, stop.lon)
        assertEquals(ParkingFormatting.place(PARK_LAT, PARK_LON), shown)
    }
}
