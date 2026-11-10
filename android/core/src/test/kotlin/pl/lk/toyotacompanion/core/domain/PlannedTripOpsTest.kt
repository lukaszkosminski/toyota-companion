package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The trip cost calculator (PRD F52, docs/SPEC.md §52); the same test names live in `backend/tests/test_domain_planned.py`. */
class PlannedTripOpsTest {

    private fun d(value: String) = BigDecimal(value)

    private fun sample(km: String, fuel: String, band: TrafficClass = TrafficClass.FLOWING) = PlanSample(d(km), d(fuel), band)

    /** Ten trips of 10 km with rates 4.0, 4.5 … 8.5 l/100 km: the quartiles are 5.0 and 7.0. */
    private fun tenTrips(): List<PlanSample> = (0 until 10).map { sample("10", BigDecimal.valueOf((40 + 5 * it).toLong(), 2).toPlainString()) }

    /** Twelve trips of 10 km with rates 1.0 … 12.0: the quartiles are 3.0 (index 2) and 9.0 (index 8). */
    private fun twelveTrips(km: String = "10"): List<PlanSample> = (1..12).map { sample(km, BigDecimal.valueOf(it.toLong(), 1).toPlainString()) }

    private fun range(result: PlannedTripResult): PlannedRange = assertIs<PlannedTripResult.Range>(result).range

    @Test
    fun `below_two_km_is_no_sample`() {
        val samples = (0 until 9).map { sample("10", "0.5") } + (0 until 5).map { sample("1.5", "0.1") }
        assertEquals(PlannedTripResult.TooFew(9), plannedTripCost(samples, d("10"), TrafficClass.FLOWING, null))
    }

    @Test
    fun `fewer_than_ten_trips_of_the_kind_give_no_range`() {
        assertEquals(PlannedTripResult.TooFew(0), plannedTripCost(emptyList(), d("10"), TrafficClass.FLOWING, null))
        // Trips of another kind do not count.
        val slow = (0 until 10).map { sample("10", "0.5", TrafficClass.SLOW) }
        assertEquals(PlannedTripResult.TooFew(0), plannedTripCost(slow, d("10"), TrafficClass.FLOWING, null))
    }

    @Test
    fun `ten_trips_give_a_range`() {
        val r = range(plannedTripCost(tenTrips(), d("10"), TrafficClass.FLOWING, null))
        assertEquals(10, r.trips)
        assertEquals(10, r.kindTrips)
        assertEquals(PlanPopulation.SIMILAR_LENGTH, r.population)
    }

    @Test
    fun `quartiles_by_index_of_sorted_rates`() {
        // Ten rates 4.0 … 8.5: index 2 (rate 5.0) and index 6 (rate 7.0); 10 km gives 0.5 and 0.7 l.
        val ten = range(plannedTripCost(tenTrips(), d("10"), TrafficClass.FLOWING, null))
        assertEquals(d("0.5"), ten.litresLow)
        assertEquals(d("0.7"), ten.litresHigh)
        // Twelve rates 1.0 … 12.0: index 2 (3.0) and index 8 (9.0); 10 km gives 0.3 and 0.9 l.
        val twelve = range(plannedTripCost(twelveTrips(), d("10"), TrafficClass.FLOWING, null))
        assertEquals(d("0.3"), twelve.litresLow)
        assertEquals(d("0.9"), twelve.litresHigh)
    }

    @Test
    fun `litres_round_to_a_tenth_half_up`() {
        // 15 km: 15 * 3.0 / 100 = 0.45 -> 0.5 and 15 * 9.0 / 100 = 1.35 -> 1.4.
        val r = range(plannedTripCost(twelveTrips(), d("15"), TrafficClass.FLOWING, null))
        assertEquals(d("0.5"), r.litresLow)
        assertEquals(d("1.4"), r.litresHigh)
    }

    @Test
    fun `money_is_whole_zloty_half_up`() {
        // 0.5 l at 5.00 zl is 2.50 zl -> 3 (half up, not to even); 0.7 l at 5.00 zl is 3.50 zl -> 4.
        val r = range(plannedTripCost(tenTrips(), d("10"), TrafficClass.FLOWING, d("5.00")))
        assertEquals(d("3"), r.costLow)
        assertEquals(d("4"), r.costHigh)
    }

    @Test
    fun `without_a_price_there_is_no_cost_and_the_litres_stay`() {
        val r = range(plannedTripCost(tenTrips(), d("10"), TrafficClass.FLOWING, null))
        assertNull(r.costLow)
        assertNull(r.costHigh)
        assertEquals(d("0.5"), r.litresLow)
    }

    @Test
    fun `zero_litre_trips_stay_in_the_population`() {
        val zeros = (0 until 10).map { sample("10", "0") }
        val r = range(plannedTripCost(zeros, d("10"), TrafficClass.FLOWING, d("6.00")))
        assertEquals(10, r.trips)
        assertEquals(d("0.0"), r.litresLow)
        assertEquals(d("0"), r.costHigh)
    }

    @Test
    fun `the_similar_length_population_is_used_when_it_has_ten_trips`() {
        // Ten trips of 10 km and ten of 30 km with 1.0 l: the planned 10 km takes trips of 5–20 km only.
        val samples = tenTrips() + (0 until 10).map { sample("30", "1.0") }
        val r = range(plannedTripCost(samples, d("10"), TrafficClass.FLOWING, null))
        assertEquals(PlanPopulation.SIMILAR_LENGTH, r.population)
        assertEquals(10, r.trips)
        assertEquals(20, r.kindTrips)
        assertEquals(d("0.5"), r.litresLow)
    }

    @Test
    fun `the_whole_kind_is_used_when_fewer_than_ten_trips_are_of_a_similar_length`() {
        // Twelve trips of 30 km (rate 1.0 each): none within 5–20 km, so the whole kind of 12 trips is the population.
        val samples = (0 until 12).map { sample("30", "0.3") }
        val r = range(plannedTripCost(samples, d("10"), TrafficClass.FLOWING, null))
        assertEquals(PlanPopulation.WHOLE_KIND, r.population)
        assertEquals(12, r.trips)
        assertEquals(12, r.kindTrips)
        assertEquals(d("0.1"), r.litresLow)
    }

    @Test
    fun `a_planned_distance_outside_the_limits_is_rejected`() {
        assertEquals(PlannedTripResult.BadKm, plannedTripCost(tenTrips(), d("0"), TrafficClass.FLOWING, null))
        assertEquals(PlannedTripResult.BadKm, plannedTripCost(tenTrips(), d("-1"), TrafficClass.FLOWING, null))
        assertEquals(PlannedTripResult.BadKm, plannedTripCost(tenTrips(), d("5001"), TrafficClass.FLOWING, null))
        assertIs<PlannedTripResult.Range>(plannedTripCost(tenTrips(), d("5000"), TrafficClass.FLOWING, null))
    }

    @Test
    fun `the_default_kind_is_the_one_with_the_most_trips`() {
        val samples = (0 until 3).map { sample("10", "0.5", TrafficClass.SLOW) } +
            (0 until 5).map { sample("10", "0.5", TrafficClass.CITY) } +
            (0 until 5).map { sample("10", "0.5", TrafficClass.FLOWING) }
        assertEquals(TrafficClass.CITY, mostDrivenBand(samples))
        assertNull(mostDrivenBand(emptyList()))
    }
}
