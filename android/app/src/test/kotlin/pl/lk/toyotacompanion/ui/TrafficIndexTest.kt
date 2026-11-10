package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.TrafficClass
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

private val BEGIN: Instant = Instant.parse("2026-10-20T08:00:00Z")

private fun ride(
    id: String,
    speed: Double?,
    km: Double = 10.0,
    fuel: String = "0.500",
    price: String? = null,
): StoredTrip {
    val record = TripRecord(
        trip = Trip(id, BEGIN, BEGIN.plusSeconds(3_600), km, BigDecimal(fuel), null),
        vin = "VINAAA111",
        averageSpeed = speed,
    )
    val cost = price?.let {
        TripCost(id, BigDecimal(it), null, null, CostSource.REFUEL)
    }
    return StoredTrip(record = record, cost = cost)
}

/** docs/SPEC.md §22: trips grouped by the average speed, the surcharge of slow driving. */
class TrafficIndexTest {
    @Test
    fun `bands include their lower bound`() {
        assertEquals(TrafficClass.SLOW, trafficClassOf(14.99))
        assertEquals(TrafficClass.CITY, trafficClassOf(15.0))
        assertEquals(TrafficClass.FLOWING, trafficClassOf(25.0))
        assertEquals(TrafficClass.HIGHWAY, trafficClassOf(40.0))
    }

    @Test
    fun `short trips and trips without speed are left out`() {
        val rows = trafficSummaries(listOf(ride("a", 20.0, km = 1.99), ride("b", null), ride("c", 0.0), ride("d", 20.0, km = 2.0)))
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].trips)
        assertEquals(TrafficClass.CITY, rows[0].trafficClass)
    }

    @Test
    fun `consumption is total fuel over total distance`() {
        val rows = trafficSummaries(listOf(ride("a", 10.0, km = 10.0, fuel = "1.0"), ride("b", 12.0, km = 30.0, fuel = "1.0")))
        assertEquals(BigDecimal("5.00"), rows.single().litresPer100)
        assertEquals(BigDecimal("40.0"), rows.single().distanceKm)
    }

    @Test
    fun `cost per 100 km uses only priced trips and is null without a price`() {
        val rows = trafficSummaries(listOf(ride("a", 10.0, km = 10.0, price = "5.00"), ride("b", 10.0, km = 30.0), ride("c", 30.0)))
        assertEquals(BigDecimal("50.00"), rows.first { it.trafficClass == TrafficClass.SLOW }.costPer100)
        assertNull(rows.first { it.trafficClass == TrafficClass.FLOWING }.costPer100)
    }

    @Test
    fun `empty bands are not listed`() {
        assertEquals(emptyList(), trafficSummaries(emptyList()))
    }

    @Test
    fun `surcharge needs ten trips on both sides`() {
        fun many(prefix: String, speed: Double, n: Int, fuel: String) = (1..n).map { ride("$prefix$it", speed, fuel = fuel) }
        val enough = trafficSummaries(many("s", 10.0, 10, "0.6") + many("f", 30.0, 10, "0.4"))
        assertEquals(BigDecimal("2.00"), slowSurcharge(enough)?.litresPer100)
        assertNull(slowSurcharge(enough)?.costPer100)
        val thin = trafficSummaries(many("s", 10.0, 9, "0.6") + many("f", 30.0, 10, "0.4"))
        assertNull(slowSurcharge(thin))
    }
}

/** The review of F19: signs, electric share, left-out trips and the guarded band function. */
class TrafficIndexReviewTest {
    @Test
    fun `a band function refuses a speed that is not a speed`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { trafficClassOf(Double.NaN) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { trafficClassOf(0.0) }
    }

    @Test
    fun `the electric share is the electric kilometres over all kilometres of the band`() {
        val begin = BEGIN
        fun rideWith(id: String, ev: Double?): StoredTrip = StoredTrip(
            TripRecord(Trip(id, begin, begin.plusSeconds(3_600), 10.0, BigDecimal("0.5"), ev), "VINAAA111", averageSpeed = 20.0),
            null,
        )
        // 4 + 20 (cut to the trip's 10) over 20 km = 70 %.
        val rows = trafficSummaries(listOf(rideWith("a", 4.0), rideWith("b", 20.0)))
        assertEquals(BigDecimal("70"), rows.single().evSharePct)
        assertNull(trafficSummaries(listOf(rideWith("c", null))).single().evSharePct?.takeIf { it.signum() > 0 })
    }

    @Test
    fun `the left-out count is the trips the bands do not count`() {
        assertEquals(2, trafficLeftOut(listOf(ride("a", null), ride("b", 20.0, km = 1.0), ride("c", 20.0))))
    }

    @Test
    fun `a negative surcharge keeps its minus and a positive one its plus`() {
        assertEquals("-1,13", TripsFormatting.signed(BigDecimal("-1.126"), 2))
        assertEquals("+0,61", TripsFormatting.signed(BigDecimal("0.612"), 2))
        assertEquals("0,00", TripsFormatting.signed(BigDecimal("0.001"), 2))
        assertEquals("-7,02 zł", TripsFormatting.signedMoney(BigDecimal("-7.02")))
        assertEquals("+7,02 zł", TripsFormatting.signedMoney(BigDecimal("7.02")))
        assertEquals("Wolna jazda wobec płynnej: -1,13 l/100 km (-7,02 zł/100 km)", Texts.trafficSurcharge("-1,13", "-7,02 zł"))
    }
}


/** docs/SPEC.md §10.3: the kind of drive on a trip row. */
class DrivingTypeLineTest {
    private fun at(speed: Double?, km: Double = 10.0) = ride("x", speed, km = km)

    @Test
    fun `each speed band has its own label with the whole km per hour`() {
        assertEquals("wolna jazda · 12 km/h", drivingTypeLine(at(12.4)))
        assertEquals("miasto · 20 km/h", drivingTypeLine(at(19.5)))
        assertEquals("płynnie · 31 km/h", drivingTypeLine(at(30.6)))
        assertEquals("trasa · 52 km/h", drivingTypeLine(at(52.0)))
    }

    @Test
    fun `the band edges follow the speed bands`() {
        assertEquals("wolna jazda · 15 km/h", drivingTypeLine(at(14.9)))
        assertEquals("miasto · 15 km/h", drivingTypeLine(at(15.0)))
        assertEquals("miasto · 25 km/h", drivingTypeLine(at(24.9)))
        assertEquals("płynnie · 25 km/h", drivingTypeLine(at(25.0)))
        assertEquals("trasa · 40 km/h", drivingTypeLine(at(40.0)))
    }

    @Test
    fun `a trip under two kilometres is a short stretch and a trip without a speed has no line`() {
        assertEquals("krótki odcinek", drivingTypeLine(at(30.0, km = 1.99)))
        assertEquals("płynnie · 30 km/h", drivingTypeLine(at(30.0, km = 2.0)))
        assertNull(drivingTypeLine(at(null)))
        assertNull(drivingTypeLine(at(0.0)))
        assertNull(drivingTypeLine(at(Double.NaN)))
        assertNull(drivingTypeLine(at(30.0, km = 0.0)))
    }
}
