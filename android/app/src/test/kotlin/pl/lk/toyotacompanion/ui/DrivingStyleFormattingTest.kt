package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun ride(id: String, start: String, km: Double, score: Int? = 80): StoredTrip {
    val begin = Instant.parse(start)
    return StoredTrip(
        TripRecord(trip = Trip(id, begin, begin.plusSeconds(900), km, BigDecimal("0.500"), null), vin = "VIN", scoreGlobal = score),
        null,
    )
}

/** docs/SPEC.md §19: a route is described without its position. */
class DrivingStyleFormattingTest {

    @Test
    fun `the typical length is the median`() {
        val odd = RouteGroup(listOf(ride("a", "2026-10-01T06:00:00Z", 12.0), ride("b", "2026-10-02T06:00:00Z", 30.0), ride("c", "2026-10-03T06:00:00Z", 12.4)))
        assertEquals(BigDecimal("12.4"), DrivingStyleFormatting.typicalKm(odd))
        val even = RouteGroup(odd.trips + ride("d", "2026-10-04T06:00:00Z", 13.0))
        assertEquals(BigDecimal("12.7"), DrivingStyleFormatting.typicalKm(even))
    }

    @Test
    fun `the usual hour is local and a tie goes to the earlier hour`() {
        // 06:10 UTC is 07:10 in Warsaw in October (CET after 25.10, CEST before: 08:10).
        val group = RouteGroup(
            listOf(
                ride("a", "2026-10-27T06:10:00Z", 10.0),
                ride("b", "2026-10-28T06:20:00Z", 10.0),
                ride("c", "2026-10-29T15:00:00Z", 10.0),
            ),
        )
        assertEquals("07:00", DrivingStyleFormatting.usualHour(group, WARSAW))
        // Before the change to winter time on 25.10 the same 06:10 UTC is 08:10 in Warsaw.
        val summer = RouteGroup(listOf(ride("d", "2026-10-24T06:10:00Z", 10.0)))
        assertEquals("08:00", DrivingStyleFormatting.usualHour(summer, WARSAW))
        val tie = RouteGroup(listOf(ride("a", "2026-10-27T15:00:00Z", 10.0), ride("b", "2026-10-28T06:00:00Z", 10.0)))
        assertEquals("07:00", DrivingStyleFormatting.usualHour(tie, WARSAW))
    }

    @Test
    fun `the route eco score is weighted by distance and skips trips without a score`() {
        val group = RouteGroup(
            listOf(
                ride("a", "2026-10-01T06:00:00Z", 30.0, score = 90),
                ride("b", "2026-10-02T06:00:00Z", 10.0, score = 50),
                ride("c", "2026-10-03T06:00:00Z", 100.0, score = null),
            ),
        )
        assertEquals(BigDecimal("80.0"), DrivingStyleFormatting.routeEco(group))
        assertNull(DrivingStyleFormatting.routeEco(RouteGroup(listOf(ride("x", "2026-10-01T06:00:00Z", 5.0, score = null)))))
    }

    @Test
    fun `points and the excess use a comma`() {
        assertEquals("86,7", DrivingStyleFormatting.points(BigDecimal("86.7")))
        assertEquals("+32,5 %", DrivingStyleFormatting.excess(BigDecimal("32.5")))
        assertEquals("\u221212,0 %", DrivingStyleFormatting.excess(BigDecimal("-12.0")))
    }

    @Test
    fun `a broken distance is left out instead of breaking the screen`() {
        val group = RouteGroup(
            listOf(
                ride("a", "2026-10-01T06:00:00Z", 12.0, score = 90),
                ride("nan", "2026-10-02T06:00:00Z", Double.NaN, score = 10),
                ride("inf", "2026-10-03T06:00:00Z", Double.POSITIVE_INFINITY, score = 10),
                ride("zero", "2026-10-04T06:00:00Z", 0.0, score = 10),
            ),
        )
        assertEquals(BigDecimal("12.0"), DrivingStyleFormatting.typicalKm(group))
        assertEquals(BigDecimal("90.0"), DrivingStyleFormatting.routeEco(group))
    }

    @Test
    fun `an anomaly names the route number shown on screen`() {
        val home = 54.262 to 18.628
        fun trip(id: String, day: Long, to: Pair<Double, Double>, fuel: String): StoredTrip {
            val start = Instant.parse("2026-10-01T06:00:00Z").plusSeconds(day * 86_400)
            return StoredTrip(
                TripRecord(
                    trip = Trip(id, start, start.plusSeconds(900), 10.0, BigDecimal(fuel), null),
                    vin = "VIN",
                    startLat = home.first,
                    startLon = home.second,
                    endLat = to.first,
                    endLon = to.second,
                ),
                null,
            )
        }
        // Route 1 is driven more often; the anomaly is on route 2.
        val first = (0L until 7L).map { trip("w$it", it, 54.367 to 18.612, "0.400") }
        val second = (7L until 12L).map { trip("s$it", it, 54.300 to 18.700, if (it == 11L) "0.800" else "0.400") }
        val view = computeDrivingStyle(first + second, WARSAW)
        assertEquals(listOf(1, 2), view.routes.map { it.number })
        assertEquals(listOf(7, 5), view.routes.map { it.trips })
        val anomaly = view.anomalies.single()
        assertEquals("s11", anomaly.trip.record.trip.id)
        assertEquals(2, anomaly.routeIndex + 1)
    }
}
