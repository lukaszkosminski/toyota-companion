package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** C45: the pure rules of a refuel the owner enters by hand. */
class ManualRefuelTest {
    private val t0: Instant = Instant.parse("2026-11-10T08:00:00Z")
    private val moment: Instant = Instant.parse("2026-11-10T10:00:00Z")
    private fun reading(ts: Instant, level: Int) = FuelReading(ts = ts, levelPct = level, odometerKm = 12_000.0)
    private fun trip(start: Instant, fuel: String) = Trip(
        id = "t-$start",
        start = start,
        end = start.plusSeconds(600),
        distanceKm = 10.0,
        fuelL = BigDecimal(fuel),
        evDistanceKm = null,
    )

    @Test
    fun `the level before is the reading minus the trips after it`() {
        // 50 % of 43 l = 21.5 l; the trip after the reading burns 2 l -> 19.5 l -> 45 %.
        val level = levelBeforeManualRefuel(reading(t0, 50), listOf(trip(t0.plusSeconds(60), "2.0")), moment)
        assertEquals(45, level)
    }

    @Test
    fun `the trips before the reading and after the moment do not count`() {
        val trips = listOf(trip(t0.minusSeconds(60), "5.0"), trip(moment.plusSeconds(60), "5.0"))
        assertEquals(50, levelBeforeManualRefuel(reading(t0, 50), trips, moment))
    }

    @Test
    fun `no reading, a reading after the moment or one older than 30 days gives no level`() {
        assertNull(levelBeforeManualRefuel(null, emptyList(), moment))
        assertNull(levelBeforeManualRefuel(reading(moment.plusSeconds(1), 50), emptyList(), moment))
        assertNull(levelBeforeManualRefuel(reading(moment.minus(Duration.ofDays(31)), 50), emptyList(), moment))
    }

    @Test
    fun `the level never goes below zero`() {
        assertEquals(0, levelBeforeManualRefuel(reading(t0, 10), listOf(trip(t0, "20.0")), moment))
    }

    @Test
    fun `a stored refuel within 12 hours is the nearby one, the closest of them`() {
        val far = refuel("far", moment.minus(Duration.ofHours(13)))
        val near = refuel("near", moment.minus(Duration.ofHours(3)))
        assertEquals("near", findNearbyRefuel(listOf(far, near), moment)?.id)
        assertNull(findNearbyRefuel(listOf(far), moment))
    }

    @Test
    fun `a manual refuel is confirmed with the price derived from the total and the level after from the liters`() {
        val refuel = manualRefuel(
            id = "manual-1",
            ts = moment,
            odometerKm = 12_000.0,
            levelBefore = 20,
            liters = BigDecimal("30"),
            totalCost = BigDecimal("180.00"),
        )
        assertEquals(RefuelStatus.CONFIRMED, refuel.status)
        assertEquals(BigDecimal("6.000"), refuel.pricePerLiter)
        assertEquals(90, refuel.levelAfter, "20 % + 30 l of 43 l is 90 %")
    }

    @Test
    fun `a refuel to full ends at 100 percent`() {
        val refuel = manualRefuel("m", moment, 1.0, 20, BigDecimal("30"), pricePerLiter = BigDecimal("6"), filledToFull = true)
        assertEquals(100, refuel.levelAfter)
        assertEquals(true, refuel.filledToFull)
    }

    @Test
    fun `liters alone are refused by the domain`() {
        assertFailsWith<IllegalArgumentException> {
            manualRefuel("m", moment, 1.0, 20, BigDecimal("30"))
        }
    }

    private fun refuel(id: String, ts: Instant) = Refuel(
        id = id,
        ts = ts,
        odometerKm = 1.0,
        levelBefore = 10,
        levelAfter = 90,
        liters = BigDecimal("30"),
        pricePerLiter = BigDecimal("6"),
        totalCost = BigDecimal("180"),
        status = RefuelStatus.CONFIRMED,
    )
}
