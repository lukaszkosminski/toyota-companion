package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
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
