package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BASE: Instant = Instant.parse("2026-10-01T08:00:00Z")

/** A trip of ten minutes, `index` times `gapDays` after the base. */
private fun drive(
    index: Int,
    km: Double,
    fuel: String,
    ev: Double? = null,
    gapDays: Double = 1.0,
    id: String = "t$index",
): Trip {
    val start = BASE.plusSeconds((index * gapDays * 86_400).toLong())
    return Trip(id, start, start.plusSeconds(600), km, BigDecimal(fuel), ev)
}

private fun many(count: Int, km: Double, fuel: String, first: Int = 0): List<Trip> = (0 until count).map { drive(first + it, km, fuel) }

private fun priced(trips: List<Trip>, perTrip: String, source: CostSource = CostSource.REFUEL): List<TripCost> =
    trips.map { TripCost(it.id, BigDecimal(perTrip), null, "r1", source) }

private fun ShortTripSummary.band(which: LengthBand): BandStats = bands.first { it.band == which }

private fun d(value: String) = BigDecimal(value)

/** The short trips and the cold engine (PRD F50, docs/SPEC.md §50); the same test names live in `backend/tests/test_domain_shorttrips.py`. */
class ShortTripOpsTest {

    @Test
    fun `band_edges_include_the_lower_bound`() {
        assertEquals(LengthBand.UNDER_2, lengthBandOf(0.1))
        assertEquals(LengthBand.UNDER_2, lengthBandOf(1.999))
        assertEquals(LengthBand.FROM_2, lengthBandOf(2.0))
        assertEquals(LengthBand.FROM_2, lengthBandOf(4.999))
        assertEquals(LengthBand.FROM_5, lengthBandOf(5.0))
        assertEquals(LengthBand.FROM_5, lengthBandOf(9.999))
        assertEquals(LengthBand.FROM_10, lengthBandOf(10.0))
        assertEquals(LengthBand.FROM_10, lengthBandOf(24.999))
        assertEquals(LengthBand.FROM_25, lengthBandOf(25.0))
        assertEquals(LengthBand.FROM_25, lengthBandOf(d("300")))
    }

    @Test
    fun `a_distance_below_the_shortest_trip_is_rejected`() {
        assertFailsWith<IllegalArgumentException> { lengthBandOf(0.099) }
        assertFailsWith<IllegalArgumentException> { lengthBandOf(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { lengthBandOf(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `broken_trips_are_left_out_and_counted`() {
        val trips = listOf(
            drive(0, 0.099, "0.01"),
            drive(1, Double.NaN, "0.1"),
            drive(2, 5.0, "-0.1"),
            drive(3, 5.0, "0.3"),
        )
        val summary = shortTripSummary(trips, emptyList())
        assertEquals(3, summary.leftOut)
        assertEquals(1, summary.usedTrips)
        assertEquals(listOf(LengthBand.FROM_5), summary.bands.map { it.band })
    }

    @Test
    fun `litres_per_100km_is_the_sum_over_the_sum_not_the_mean_of_the_rates`() {
        // 20.0, 6.67, 10.0, 10.0, 10.0 l/100 km: the mean of the rates is 11.33, the sum over the sum is 10.00.
        val trips = listOf(drive(0, 0.5, "0.1"), drive(1, 1.5, "0.1"), drive(2, 1.0, "0.1"), drive(3, 1.0, "0.1"), drive(4, 1.0, "0.1"))
        val row = shortTripSummary(trips, emptyList()).band(LengthBand.UNDER_2)
        assertEquals(d("10.00"), row.lPer100Km)
        assertEquals(d("5.0"), row.distanceKm)
        assertEquals(d("0.500"), row.fuelL)
    }

    @Test
    fun `a_trip_with_zero_litres_stays_in_the_sums`() {
        val row = shortTripSummary(many(5, 1.0, "0"), emptyList()).band(LengthBand.UNDER_2)
        assertEquals(5, row.trips)
        assertEquals(d("0.00"), row.lPer100Km)
        val mixed = many(4, 1.0, "0.2") + drive(4, 1.0, "0")
        assertEquals(d("16.00"), shortTripSummary(mixed, emptyList()).band(LengthBand.UNDER_2).lPer100Km)
    }

    @Test
    fun `a_band_shows_its_rates_from_five_trips`() {
        val four = shortTripSummary(many(4, 3.0, "0.2"), emptyList()).band(LengthBand.FROM_2)
        assertEquals(4, four.trips)
        assertNull(four.lPer100Km)
        assertNull(four.costPer100Km)
        assertNull(four.evSharePct)
        val five = shortTripSummary(many(5, 3.0, "0.2"), emptyList()).band(LengthBand.FROM_2)
        assertEquals(d("6.67"), five.lPer100Km)
    }

    @Test
    fun `shares_of_trips_and_km_are_whole_percent_half_up`() {
        val trips = listOf(drive(0, 1.0, "0.1"), drive(1, 10.0, "0.4"), drive(2, 10.0, "0.4"))
        val summary = shortTripSummary(trips, emptyList())
        val short = summary.band(LengthBand.UNDER_2)
        val long = summary.band(LengthBand.FROM_10)
        assertEquals(d("33"), short.tripSharePct)
        assertEquals(d("67"), long.tripSharePct)
        assertEquals(d("5"), short.kmSharePct)
        assertEquals(d("95"), long.kmSharePct)
        assertEquals(1, summary.shortTrips)
        assertEquals(d("33"), summary.shortTripSharePct)
        assertEquals(d("5"), summary.shortKmSharePct)
    }

    @Test
    fun `electric_share_is_cut_to_the_distance_of_the_trip`() {
        val trips = listOf(
            drive(0, 2.0, "0.1", ev = 3.0),
            drive(1, 2.0, "0.1", ev = 1.0),
            drive(2, 2.0, "0.1", ev = null),
            drive(3, 2.0, "0.1", ev = 0.0),
            drive(4, 2.0, "0.1", ev = 0.0),
        )
        assertEquals(d("30"), shortTripSummary(trips, emptyList()).band(LengthBand.FROM_2).evSharePct)
        assertNull(shortTripSummary(many(5, 2.0, "0.1"), emptyList()).band(LengthBand.FROM_2).evSharePct)
    }

    @Test
    fun `cost_per_100km_is_only_from_priced_trips_and_never_zero`() {
        val trips = many(5, 1.0, "0.1")
        assertNull(shortTripSummary(trips, emptyList()).band(LengthBand.UNDER_2).costPer100Km)
        // An amount with the source NONE is not a price.
        assertNull(shortTripSummary(trips, priced(trips, "0.50", CostSource.NONE)).band(LengthBand.UNDER_2).costPer100Km)
        assertEquals(d("50.00"), shortTripSummary(trips, priced(trips, "0.50")).band(LengthBand.UNDER_2).costPer100Km)
        // Unpriced trips add their km to the band, not to the price: 2.5 zl over 5 priced km.
        val more = trips + drive(9, 1.0, "0.1")
        assertEquals(d("50.00"), shortTripSummary(more, priced(trips.take(5), "0.50")).band(LengthBand.UNDER_2).costPer100Km)
    }

    @Test
    fun `the_reference_is_all_trips_from_10_km_together`() {
        val trips = many(5, 10.0, "0.4") + many(5, 30.0, "1.2", first = 5)
        val summary = shortTripSummary(trips, emptyList())
        val reference = assertNotNull(summary.reference)
        assertEquals(10, reference.trips)
        assertEquals(d("200.0"), reference.distanceKm)
        assertEquals(d("4.00"), reference.lPer100Km)
        assertNull(reference.costPer100Km)
        val onlyShort = shortTripSummary(many(12, 1.0, "0.1"), emptyList())
        assertNull(onlyShort.reference)
        assertNull(onlyShort.band(LengthBand.UNDER_2).excessL)
    }

    @Test
    fun `the_excess_needs_ten_trips_in_the_band_and_in_the_reference`() {
        val reference = many(10, 30.0, "1.2", first = 20)
        assertNotNull(shortTripSummary(many(10, 1.0, "0.2") + reference, emptyList()).band(LengthBand.UNDER_2).excessLPer100Km)
        assertNull(shortTripSummary(many(9, 1.0, "0.2") + reference, emptyList()).band(LengthBand.UNDER_2).excessLPer100Km)
        val nineReference = many(9, 30.0, "1.2", first = 20)
        assertNull(shortTripSummary(many(10, 1.0, "0.2") + nineReference, emptyList()).band(LengthBand.UNDER_2).excessLPer100Km)
        // The reference bands themselves have no excess.
        assertNull(shortTripSummary(many(10, 1.0, "0.2") + reference, emptyList()).band(LengthBand.FROM_25).excessLPer100Km)
    }

    @Test
    fun `the_excess_over_the_reference_in_rate_and_litres`() {
        // Band: 10 trips of 2 km with 0.2 l = 10.00 l/100 km; reference: 10 trips of 30 km with 1.2 l = 4.00 l/100 km.
        val trips = many(10, 2.0, "0.2") + many(10, 30.0, "1.2", first = 20)
        val row = shortTripSummary(trips, emptyList()).band(LengthBand.FROM_2)
        assertEquals(d("10.00"), row.lPer100Km)
        assertEquals(d("6.00"), row.excessLPer100Km)
        // 2.0 l burnt - 4.00/100 * 20 km = 1.2 l more.
        assertEquals(d("1.20"), row.excessL)
    }

    @Test
    fun `the_excess_in_litres_uses_the_unrounded_reference_rate`() {
        // The reference rate is 4.145 l/100 km (shown 4.15). From the rate 4.15 the excess would be 0.17 l, from the true rate 0.18 l.
        val reference = many(10, 30.0, "1.2435", first = 20)
        val bandTrips = many(10, 1.99, "0.1")
        val row = shortTripSummary(bandTrips + reference, emptyList()).band(LengthBand.UNDER_2)
        assertEquals(d("5.03"), row.lPer100Km)
        assertEquals(d("0.88"), row.excessLPer100Km)
        assertEquals(d("0.18"), row.excessL)
    }

    @Test
    fun `the_excess_in_money_is_litres_times_the_price_of_the_band`() {
        val short = many(10, 2.0, "0.2")
        val reference = many(10, 30.0, "1.2", first = 20)
        val costs = priced(short, "1.50") + priced(reference, "7.20")
        val row = shortTripSummary(short + reference, costs).band(LengthBand.FROM_2)
        // The band paid 15.00 zl for 2.0 l = 7.50 zl/l; 1.2 l more = 9.00 zl.
        assertEquals(d("1.20"), row.excessL)
        assertEquals(d("9.00"), row.excessPln)
        assertNull(shortTripSummary(short + reference, emptyList()).band(LengthBand.FROM_2).excessPln)
    }

    @Test
    fun `a_negative_excess_is_kept_with_its_sign`() {
        // Short trips on the battery only: 0 l against 4.00 l/100 km of the reference.
        val trips = many(10, 2.0, "0") + many(10, 30.0, "1.2", first = 20)
        val row = shortTripSummary(trips, emptyList()).band(LengthBand.FROM_2)
        assertEquals(d("-4.00"), row.excessLPer100Km)
        assertEquals(d("-0.80"), row.excessL)
    }

    @Test
    fun `the_total_sums_only_the_bands_with_an_excess`() {
        val reference = many(10, 30.0, "1.2", first = 40)
        val under = many(10, 1.0, "0.2", first = 0)
        val fromTwo = many(10, 2.0, "0.2", first = 10)
        val fromFive = many(9, 6.0, "0.5", first = 20)
        val summary = shortTripSummary(under + fromTwo + fromFive + reference, emptyList())
        // UNDER_2: 2.0 l - 4.00/100 * 10 km = 1.6 l; FROM_2: 1.2 l; FROM_5 has 9 trips: no excess, not in the total.
        assertEquals(d("2.80"), summary.totalExcessL)
        assertNull(summary.totalExcessPln)
        assertEquals(29, summary.shortTrips)
        assertNull(shortTripSummary(fromFive + reference, emptyList()).totalExcessL)
    }

    @Test
    fun `the_total_in_money_needs_a_price_in_every_counted_band`() {
        val reference = many(10, 30.0, "1.2", first = 40)
        val under = many(10, 1.0, "0.2", first = 0)
        val fromTwo = many(10, 2.0, "0.2", first = 10)
        val allPriced = priced(under, "1.50") + priced(fromTwo, "1.50") + priced(reference, "7.20")
        val summary = shortTripSummary(under + fromTwo + reference, allPriced)
        // 1.6 l * 7.50 zl/l + 1.2 l * 7.50 zl/l = 12.00 + 9.00 zl
        assertEquals(d("21.00"), summary.totalExcessPln)
        val partly = priced(under, "1.50") + priced(reference, "7.20")
        assertNull(shortTripSummary(under + fromTwo + reference, partly).totalExcessPln)
    }

    @Test
    fun `cold_and_warm_starts_follow_the_gap_since_the_previous_trip`() {
        val first = Instant.parse("2026-10-01T08:00:00Z")

        fun after(gap: Duration, id: String, previousEnd: Instant): Trip {
            val start = previousEnd.plus(gap)
            return Trip(id, start, start.plusSeconds(300), 3.0, d("0.2"), null)
        }

        val zero = Trip("a", first, first.plusSeconds(300), 3.0, d("0.2"), null)
        val warm = after(Duration.ofMinutes(2 * 60 + 59), "b", zero.end)
        val cold = after(Duration.ofHours(3), "c", warm.end)
        val threeWeeks = after(Duration.ofDays(21), "d", cold.end)
        val hole = after(Duration.ofDays(21).plusSeconds(1), "e", threeWeeks.end)
        val overlap = after(Duration.ofMinutes(-2), "f", hole.end)
        val kinds = tripStartKinds(listOf(zero, warm, cold, threeWeeks, hole, overlap))
        assertEquals(
            mapOf(
                "a" to StartKind.UNKNOWN,
                "b" to StartKind.WARM,
                "c" to StartKind.COLD,
                "d" to StartKind.COLD,
                "e" to StartKind.UNKNOWN,
                "f" to StartKind.WARM,
            ),
            kinds,
        )
    }

    @Test
    fun `the_previous_trip_is_any_trip_also_one_the_bands_leave_out`() {
        val first = Instant.parse("2026-10-01T08:00:00Z")
        val tiny = Trip("tiny", first, first.plusSeconds(60), 0.05, d("0"), null)
        val nextStart = tiny.end.plus(Duration.ofHours(1))
        val after = Trip("after", nextStart, nextStart.plusSeconds(300), 3.0, d("0.2"), null)
        assertEquals(StartKind.WARM, tripStartKinds(listOf(tiny, after)).getValue("after"))
        // The tiny trip itself is not in the bands.
        assertEquals(1, shortTripSummary(listOf(tiny, after), emptyList()).leftOut)
    }

    @Test
    fun `start_kinds_do_not_depend_on_the_order_and_ties_break_by_id`() {
        val a = drive(0, 3.0, "0.2", id = "a")
        val b = drive(1, 3.0, "0.2", id = "b")
        val sameStart1 = Trip("x1", b.start.plus(Duration.ofDays(1)), b.start.plus(Duration.ofDays(1)).plusSeconds(300), 3.0, d("0.2"), null)
        val sameStart2 = Trip("x2", sameStart1.start, sameStart1.end, 3.0, d("0.2"), null)
        val forward = tripStartKinds(listOf(a, b, sameStart1, sameStart2))
        val backward = tripStartKinds(listOf(sameStart2, sameStart1, b, a))
        assertEquals(forward, backward)
        assertEquals(StartKind.COLD, forward.getValue("x1"))
        // x2 follows x1 by (start, id) with an overlap of its whole length: warm.
        assertEquals(StartKind.WARM, forward.getValue("x2"))
    }

    @Test
    fun `the_cold_split_needs_ten_trips_on_both_sides`() {
        val cold = (0 until 10).map { drive(it * 2, 3.0, "0.30", gapDays = 1.0) }
        // Warm trips start 30 minutes after the cold one of the same index ended.
        val warm = cold.mapIndexed { i, c ->
            Trip("w$i", c.end.plusSeconds(1_800), c.end.plusSeconds(2_400), 3.0, d("0.15"), null)
        }
        var row = shortTripSummary(cold + warm, emptyList()).band(LengthBand.FROM_2)
        assertEquals(9, row.coldTrips)
        assertEquals(10, row.warmTrips)
        assertEquals(1, row.unknownStartTrips)
        assertNull(row.coldSplit)
        val more = drive(20, 3.0, "0.30")
        row = shortTripSummary(cold + warm + more, emptyList()).band(LengthBand.FROM_2)
        assertEquals(10, row.coldTrips)
        assertEquals(10, row.warmTrips)
        val split = assertNotNull(row.coldSplit)
        assertEquals(d("10.00"), split.coldLPer100Km)
        assertEquals(d("5.00"), split.warmLPer100Km)
        assertEquals(d("5.00"), split.differenceLPer100Km)
    }

    @Test
    fun `unknown_starts_are_in_the_band_but_not_in_the_split`() {
        val row = shortTripSummary(many(3, 3.0, "0.2"), emptyList()).band(LengthBand.FROM_2)
        assertEquals(3, row.trips)
        assertEquals(2, row.coldTrips)
        assertEquals(0, row.warmTrips)
        assertEquals(1, row.unknownStartTrips)
        assertNull(row.coldSplit)
    }

    @Test
    fun `an_empty_list_or_only_broken_trips_gives_an_empty_summary`() {
        for (trips in listOf(emptyList(), listOf(drive(0, 0.05, "0")))) {
            val summary = shortTripSummary(trips, emptyList())
            assertTrue(summary.bands.isEmpty())
            assertNull(summary.reference)
            assertNull(summary.shortTripSharePct)
            assertNull(summary.shortKmSharePct)
            assertNull(summary.totalExcessL)
            assertEquals(0, summary.usedTrips)
        }
        assertEquals(1, shortTripSummary(listOf(drive(0, 0.05, "0")), emptyList()).leftOut)
    }
}
