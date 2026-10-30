package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

private const val VIN = "VINAAA111"

/** A zone with a real offset (and a DST shift), so a UTC-only test would not prove anything. */
private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/**
 * The list rows arrive from `SyncEngine.trips()` newest first; the grouping is a
 * pure function, so the rows are built here without any repository (docs/SPEC.md §10.2).
 */
private fun stored(
    id: String,
    start: Instant,
    km: Double = 12.0,
    fuelL: String = "0.600",
    cost: TripCost? = null,
): StoredTrip = StoredTrip(
    record = TripRecord(
        trip = Trip(
            id = id,
            start = start,
            end = start.plusSeconds(1_800),
            distanceKm = km,
            fuelL = BigDecimal(fuelL),
            evDistanceKm = null,
        ),
        vin = VIN,
    ),
    cost = cost,
)

private fun costOf(id: String, value: String, source: CostSource = CostSource.REFUEL): TripCost =
    TripCost(
        tripId = id,
        costPln = BigDecimal(value),
        pricePerLiter = BigDecimal("6.140"),
        refuelId = "refuel-1",
        source = source,
    )

private fun amount(expected: String, actual: BigDecimal?, message: String = "") {
    val value = requireNotNull(actual) { message }
    assertEquals(0, BigDecimal(expected).compareTo(value), "$message expected $expected got $value")
}

/** docs/SPEC.md §10.2: months of the trip start in the given zone, newest month first. */
class TripsGroupingTest {
    @Test
    fun `no trips means no months`() {
        assertEquals(emptyList(), groupTripsByMonth(emptyList(), WARSAW))
    }

    @Test
    fun `months are ordered newest first`() {
        val trips = listOf(
            stored("t-aug", Instant.parse("2026-08-10T09:00:00Z")),
            stored("t-oct", Instant.parse("2026-10-05T09:00:00Z")),
            stored("t-sep", Instant.parse("2026-09-20T09:00:00Z")),
        )

        val groups = groupTripsByMonth(trips, WARSAW)

        assertEquals(
            listOf(MonthKey(2026, 10), MonthKey(2026, 9), MonthKey(2026, 8)),
            groups.map { it.month },
        )
    }

    @Test
    fun `month is counted in the given zone`() {
        // 22:30 UTC on 30 September is 00:30 local on 1 October (Europe/Warsaw, CEST = UTC+2).
        val trips = listOf(
            stored("t-late-sep", Instant.parse("2026-09-30T21:59:00Z")),
            stored("t-early-oct", Instant.parse("2026-09-30T22:30:00Z")),
        )

        val groups = groupTripsByMonth(trips, WARSAW)

        assertEquals(listOf(MonthKey(2026, 10), MonthKey(2026, 9)), groups.map { it.month })
        assertEquals(listOf("t-early-oct"), groups[0].trips.map { it.record.id })
        assertEquals(listOf("t-late-sep"), groups[1].trips.map { it.record.id })
    }

    @Test
    fun `trips inside a month keep the newest first order`() {
        val trips = listOf(
            stored("t-new", Instant.parse("2026-10-20T18:00:00Z")),
            stored("t-old", Instant.parse("2026-10-01T06:00:00Z")),
        )

        assertEquals(listOf("t-new", "t-old"), groupTripsByMonth(trips, WARSAW).single().trips.map { it.record.id })
    }

    @Test
    fun `years are separate months`() {
        val trips = listOf(
            stored("t-2025", Instant.parse("2025-12-31T23:00:00Z")),
            stored("t-2026", Instant.parse("2026-01-01T00:30:00Z")),
        )

        assertEquals(
            listOf(MonthKey(2026, 1), MonthKey(2025, 12)),
            groupTripsByMonth(trips, ZoneId.of("UTC")).map { it.month },
        )
    }

    @Test
    fun `month sums kilometers liters and cost`() {
        val trips = listOf(
            stored("t1", Instant.parse("2026-10-10T08:00:00Z"), km = 14.6, fuelL = "0.600", cost = costOf("t1", "3.68")),
            stored("t2", Instant.parse("2026-10-11T08:00:00Z"), km = 12.3, fuelL = "0.500", cost = costOf("t2", "3.07")),
        )

        val group = groupTripsByMonth(trips, WARSAW).single()

        amount("26.9", group.totalKm, "kilometers")
        amount("1.100", group.totalLiters, "liters")
        amount("6.75", group.totalCost, "cost")
        assertEquals(0, group.tripsWithoutCost)
    }

    @Test
    fun `trips without a cost are counted and left out of the sum`() {
        val trips = listOf(
            stored("t1", Instant.parse("2026-10-10T08:00:00Z"), cost = costOf("t1", "3.68")),
            stored("t2", Instant.parse("2026-10-11T08:00:00Z")), // no cost row at all
        )

        val group = groupTripsByMonth(trips, WARSAW).single()

        assertEquals(1, group.tripsWithoutCost)
        amount("3.68", group.totalCost, "cost of the priced trips only")
    }

    @Test
    fun `cost with source none counts as no price`() {
        val trips = listOf(
            stored("t1", Instant.parse("2026-10-10T08:00:00Z"), cost = costOf("t1", "0.00", CostSource.NONE)),
        )

        val group = groupTripsByMonth(trips, WARSAW).single()

        assertEquals(1, group.tripsWithoutCost)
        assertTrue(group.totalCost == null, "a month without a price must not show 0,00 zl")
    }

    @Test
    fun `cost without an amount counts as no price`() {
        val noAmount = TripCost("t1", null, BigDecimal("6.140"), "refuel-1", CostSource.REFUEL)

        val group = groupTripsByMonth(
            listOf(stored("t1", Instant.parse("2026-10-10T08:00:00Z"), cost = noAmount)),
            WARSAW,
        ).single()

        assertEquals(1, group.tripsWithoutCost)
        assertTrue(group.totalCost == null, "no amount means no sum")
    }

    @Test
    fun `default price sums like a refuel price`() {
        val trips = listOf(
            stored("t1", Instant.parse("2026-10-10T08:00:00Z"), cost = costOf("t1", "3.59", CostSource.DEFAULT)),
        )

        amount("3.59", groupTripsByMonth(trips, WARSAW).single().totalCost, "default price")
    }

    @Test
    fun `month without any cost has no sum`() {
        val group = groupTripsByMonth(
            listOf(stored("t1", Instant.parse("2026-10-10T08:00:00Z"))),
            WARSAW,
        ).single()

        assertEquals(MonthKey(2026, 10), group.month)
        assertTrue(group.totalCost == null, "expected no cost")
        assertEquals(1, group.tripsWithoutCost)
    }
}
