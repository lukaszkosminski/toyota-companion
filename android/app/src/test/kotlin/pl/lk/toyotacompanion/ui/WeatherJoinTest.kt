package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.WeatherRow
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.weather.WeatherDay

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
private val TODAY: LocalDate = LocalDate.parse("2026-11-01")

private fun trip(id: String, start: String, lat: Double? = 54.372, lon: Double? = 18.638): StoredTrip {
    val begin = Instant.parse(start)
    val record = TripRecord(
        trip = Trip(id, begin, begin.plusSeconds(600), 10.0, BigDecimal("0.5"), null),
        vin = "VINAAA111",
        startLat = lat,
        startLon = lon,
    )
    return StoredTrip(record, null)
}

private fun row(date: String, mean: Double?, lat: String = "54.37", lon: String = "18.64") = WeatherRow(
    lat,
    lon,
    WeatherDay(LocalDate.parse(date), mean, null, null, 0.0, null, null),
    Instant.parse("2026-11-02T10:00:00Z"),
)

/** docs/SPEC.md §23: which cached day belongs to a trip, and what still has to be fetched. */
class WeatherJoinTest {
    @Test
    fun `the place is the start point rounded half up`() {
        assertEquals(WeatherPlace("54.36", "18.64"), weatherPlaceOf(trip("a", "2026-10-30T08:00:00Z", 54.3649, 18.6351).record))
        assertEquals(WeatherPlace("54.37", "18.64"), weatherPlaceOf(trip("a", "2026-10-30T08:00:00Z", 54.365, 18.635).record))
    }

    @Test
    fun `no position, a broken one or one out of range gives no place`() {
        assertNull(weatherPlaceOf(trip("a", "2026-10-30T08:00:00Z", null, null).record))
        assertNull(weatherPlaceOf(trip("a", "2026-10-30T08:00:00Z", Double.NaN, 18.0).record))
        assertNull(weatherPlaceOf(trip("a", "2026-10-30T08:00:00Z", 91.0, 18.0).record))
    }

    @Test
    fun `the day is the local date of the start`() {
        // 23:30 UTC on 30.10 is already 31.10 in Warsaw (UTC+1).
        assertEquals(LocalDate.parse("2026-10-31"), weatherDateOf(trip("a", "2026-10-30T23:30:00Z"), WARSAW))
        assertEquals(LocalDate.parse("2026-10-30"), weatherDateOf(trip("a", "2026-10-30T22:30:00Z"), WARSAW))
    }

    @Test
    fun `a trip gets the cached day of its place and local date`() {
        val joined = tripWeather(
            listOf(trip("a", "2026-10-30T23:30:00Z"), trip("b", "2026-10-30T08:00:00Z"), trip("c", "2026-10-29T08:00:00Z")),
            listOf(row("2026-10-31", 3.5), row("2026-10-30", 8.0)),
            WARSAW,
        )
        assertEquals(BigDecimal("3.5"), joined.getValue("a").tempMean)
        assertEquals(BigDecimal("8.0"), joined.getValue("b").tempMean)
        assertNull(joined["c"])
    }

    @Test
    fun `another place is not the same day`() {
        val joined = tripWeather(listOf(trip("a", "2026-10-30T08:00:00Z")), listOf(row("2026-10-30", 8.0, lat = "52.23")), WARSAW)
        assertEquals(emptyMap(), joined)
    }

    @Test
    fun `a day without a temperature stays a null, not a zero`() {
        val joined = tripWeather(listOf(trip("a", "2026-10-30T08:00:00Z")), listOf(row("2026-10-30", null)), WARSAW)
        assertNull(joined.getValue("a").tempMean)
    }

    @Test
    fun `the plan asks one range per place for the days that are missing`() {
        val plan = planWeatherFetch(
            listOf(trip("a", "2026-09-01T08:00:00Z"), trip("b", "2026-09-05T08:00:00Z"), trip("c", "2026-09-03T08:00:00Z")),
            listOf(row("2026-09-03", 10.0)),
            WARSAW,
            TODAY,
        )
        assertEquals(listOf(WeatherRequest(WeatherPlace("54.37", "18.64"), LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-05"), 2)), plan.requests)
        assertEquals(0, plan.requestsLeft)
    }

    @Test
    fun `today and trips without a position are never requested`() {
        val plan = planWeatherFetch(
            listOf(trip("a", "2026-11-01T08:00:00Z"), trip("b", "2026-09-01T08:00:00Z", null, null)),
            emptyList(),
            WARSAW,
            TODAY,
        )
        assertEquals(emptyList(), plan.requests)
    }

    @Test
    fun `an empty recent day is asked for again but an old empty one is not`() {
        val recent = planWeatherFetch(listOf(trip("a", "2026-10-28T08:00:00Z")), listOf(row("2026-10-28", null)), WARSAW, TODAY)
        assertEquals(1, recent.requests.size)
        val old = planWeatherFetch(listOf(trip("a", "2026-09-01T08:00:00Z")), listOf(row("2026-09-01", null)), WARSAW, TODAY)
        assertEquals(emptyList(), old.requests)
    }

    @Test
    fun `at most ten places per tap, the busiest first, and the rest is counted`() {
        val trips = (1..12).flatMap { i ->
            val lat = 50.0 + i
            (1..i).map { n -> trip("t$i-$n", "2026-09-${"%02d".format(n)}T08:00:00Z", lat, 18.0) }
        }
        val plan = planWeatherFetch(trips, emptyList(), WARSAW, TODAY)
        assertEquals(10, plan.requests.size)
        assertEquals(2, plan.requestsLeft)
        assertEquals(12, plan.requests.first().trips)
        assertEquals(3, plan.requests.last().trips)
    }

    @Test
    fun `far apart runs of one place are separate requests`() {
        val plan = planWeatherFetch(
            listOf(trip("a", "2025-01-10T08:00:00Z"), trip("b", "2025-01-12T08:00:00Z"), trip("c", "2026-10-20T08:00:00Z")),
            emptyList(),
            WARSAW,
            TODAY,
        )
        assertEquals(2, plan.requests.size)
        assertEquals(LocalDate.parse("2025-01-10") to LocalDate.parse("2025-01-12"), plan.requests[0].start to plan.requests[0].end)
        assertEquals(2, plan.requests[0].trips)
        assertEquals(LocalDate.parse("2026-10-20"), plan.requests[1].start)
    }

    @Test
    fun `a recent day with a temperature but no precipitation is asked for again`() {
        val partial = WeatherRow(
            "54.37",
            "18.64",
            WeatherDay(LocalDate.parse("2026-10-28"), 4.0, null, null, null, null, null),
            Instant.parse("2026-11-02T10:00:00Z"),
        )
        val plan = planWeatherFetch(listOf(trip("a", "2026-10-28T08:00:00Z")), listOf(partial), WARSAW, TODAY)
        assertEquals(1, plan.requests.size)
    }

    @Test
    fun `the window of a stale day ends exactly ten days back`() {
        val edge = planWeatherFetch(listOf(trip("a", "2026-10-22T08:00:00Z")), listOf(row("2026-10-22", null)), WARSAW, TODAY)
        assertEquals(1, edge.requests.size)
        val past = planWeatherFetch(listOf(trip("a", "2026-10-21T08:00:00Z")), listOf(row("2026-10-21", null)), WARSAW, TODAY)
        assertEquals(emptyList(), past.requests)
    }

    @Test
    fun `negative coordinates make one place, not two`() {
        val a = weatherPlaceOf(trip("a", "2026-10-30T08:00:00Z", -33.8651, -70.0004).record)
        val b = weatherPlaceOf(trip("b", "2026-10-30T08:00:00Z", -33.8649, -69.9996).record)
        assertEquals(WeatherPlace("-33.87", "-70.00"), a)
        assertEquals(WeatherPlace("-33.86", "-70.00"), b)
        assertEquals(WeatherPlace("0.00", "0.00"), weatherPlaceOf(trip("c", "2026-10-30T08:00:00Z", -0.0004, -0.0004).record))
    }

    @Test
    fun `a day cached before the weather code existed is fetched once more, a newer one without a code is not`() {
        val old = row("2026-09-01", 8.0)
        val oldPlan = planWeatherFetch(listOf(trip("a", "2026-09-01T08:00:00Z")), listOf(old.copy(fetchedAt = Instant.parse("2026-11-01T10:00:00Z"))), WARSAW, TODAY)
        assertEquals(1, oldPlan.requests.size)
        val recentlyFetched = old.copy(fetchedAt = Instant.parse("2026-11-01T19:00:00Z"))
        assertEquals(emptyList(), planWeatherFetch(listOf(trip("a", "2026-09-01T08:00:00Z")), listOf(recentlyFetched), WARSAW, TODAY).requests)
        val withCode = old.copy(day = old.day.copy(weatherCode = 61))
        assertEquals(emptyList(), planWeatherFetch(listOf(trip("a", "2026-09-01T08:00:00Z")), listOf(withCode), WARSAW, TODAY).requests)
    }

    @Test
    fun `the days of the trips come by trip id with the code`() {
        val withCode = row("2026-10-30", 8.0).let { it.copy(day = it.day.copy(weatherCode = 0)) }
        val days = tripWeatherDays(listOf(trip("a", "2026-10-30T08:00:00Z"), trip("b", "2026-10-29T08:00:00Z")), listOf(withCode), WARSAW)
        assertEquals(0, days.getValue("a").weatherCode)
        assertNull(days["b"])
    }

    @Test
    fun `a trip of today goes to the forecast list and not to the archive requests`() {
        val plan = planWeatherFetch(listOf(trip("a", "2026-11-01T08:00:00Z"), trip("b", "2026-09-01T08:00:00Z")), emptyList(), WARSAW, TODAY, now = Instant.parse("2026-11-01T12:00:00Z"))
        assertEquals(listOf(WeatherPlace("54.37", "18.64")), plan.todayPlaces)
        assertEquals(1, plan.requests.size)
        assertEquals(LocalDate.parse("2026-09-01"), plan.requests.single().start)
    }

    @Test
    fun `a row of today younger than three hours is kept and an older one is asked again`() {
        val todayRow = row("2026-11-01", 5.0).copy(fetchedAt = Instant.parse("2026-11-01T10:00:00Z"))
        val trips = listOf(trip("a", "2026-11-01T08:00:00Z"))
        assertEquals(emptyList(), planWeatherFetch(trips, listOf(todayRow), WARSAW, TODAY, now = Instant.parse("2026-11-01T12:00:00Z")).todayPlaces)
        assertEquals(1, planWeatherFetch(trips, listOf(todayRow), WARSAW, TODAY, now = Instant.parse("2026-11-01T13:30:00Z")).todayPlaces.size)
    }
}
