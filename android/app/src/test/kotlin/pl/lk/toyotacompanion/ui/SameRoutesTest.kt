package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private val DAY_1: Instant = Instant.parse("2026-10-01T06:00:00Z")

/** 0.001 degree of latitude is about 111 m. */
private const val HOME_LAT = 54.262
private const val HOME_LON = 18.628
private const val WORK_LAT = 54.367
private const val WORK_LON = 18.612

private fun drive(
    id: String,
    day: Long,
    from: Pair<Double, Double>? = HOME_LAT to HOME_LON,
    to: Pair<Double, Double>? = WORK_LAT to WORK_LON,
): StoredTrip {
    val start = DAY_1.plusSeconds(day * 86_400)
    val trip = Trip(id, start, start.plusSeconds(1_800), 12.4, BigDecimal("0.500"), 6.0)
    return StoredTrip(
        TripRecord(
            trip = trip,
            vin = "VIN",
            startLat = from?.first,
            startLon = from?.second,
            endLat = to?.first,
            endLon = to?.second,
        ),
        null,
    )
}

/** docs/SPEC.md §19: trips between the same two places, in the same direction. */
class SameRoutesTest {

    @Test
    fun `trips within 300 m at both ends are one route`() {
        val trips = listOf(
            drive("a", 0),
            drive("b", 1, from = (HOME_LAT + 0.002) to HOME_LON),
            drive("c", 2, to = (WORK_LAT - 0.002) to WORK_LON),
        )
        val routes = sameRoutes(trips)
        assertEquals(1, routes.size)
        assertEquals(listOf("a", "b", "c"), routes.single().trips.map { it.record.trip.id })
        assertEquals("a", routes.single().pattern.trip.id)
    }

    @Test
    fun `an end further than 300 m opens another route`() {
        val trips = listOf(drive("a", 0), drive("b", 1), drive("c", 2), drive("far", 3, to = (WORK_LAT + 0.003) to WORK_LON))
        val routes = sameRoutes(trips, minTrips = 1)
        assertEquals(listOf(listOf("a", "b", "c"), listOf("far")), routes.map { r -> r.trips.map { it.record.trip.id } })
    }

    @Test
    fun `the way back is a different route`() {
        val there = (0L until 3L).map { drive("t$it", it) }
        val back = (3L until 6L).map { drive("b$it", it, from = WORK_LAT to WORK_LON, to = HOME_LAT to HOME_LON) }
        assertEquals(2, sameRoutes(there + back).size)
    }

    @Test
    fun `fewer than three trips are not a route`() {
        assertTrue(sameRoutes(listOf(drive("a", 0), drive("b", 1))).isEmpty())
    }

    @Test
    fun `trips without positions join nothing`() {
        val trips = listOf(drive("a", 0), drive("b", 1), drive("c", 2, from = null), drive("d", 3, to = null))
        assertTrue(sameRoutes(trips).isEmpty())
        val nan = drive("e", 4, from = Double.NaN to HOME_LON)
        assertTrue(sameRoutes(listOf(drive("a", 0), drive("b", 1), nan)).isEmpty())
    }

    @Test
    fun `input order does not matter and the most driven route comes first`() {
        val work = (0L until 3L).map { drive("w$it", it) }
        val shop = (3L until 7L).map { drive("s$it", it, to = 54.300 to 18.700) }
        val routes = sameRoutes((shop + work).shuffled(java.util.Random(7)))
        assertEquals(listOf(4, 3), routes.map { it.trips.size })
        assertEquals("s3", routes.first().pattern.trip.id)
    }

    @Test
    fun `the pattern does not drift with the trips that joined it`() {
        // Each step is 250 m from the previous trip, but the third is 500 m from the pattern.
        val trips = listOf(
            drive("a", 0),
            drive("b", 1, to = (WORK_LAT + 0.00225) to WORK_LON),
            drive("c", 2, to = (WORK_LAT + 0.0045) to WORK_LON),
        )
        val routes = sameRoutes(trips, minTrips = 1)
        assertEquals(listOf(listOf("a", "b"), listOf("c")), routes.map { r -> r.trips.map { it.record.trip.id } })
    }

    @Test
    fun `a tie goes to the route driven most recently`() {
        val old = (0L until 3L).map { drive("o$it", it) }
        val recent = (3L until 6L).map { drive("r$it", it, to = 54.300 to 18.700) }
        assertEquals("r3", sameRoutes(old + recent).first().pattern.trip.id)
    }
}
