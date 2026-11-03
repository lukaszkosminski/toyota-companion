@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertBd(expected: String, actual: BigDecimal?, label: String = "value") {
    val e = BigDecimal(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

private val START: Instant = Instant.parse("2026-01-05T08:00:00Z")

private fun trip(id: String, km: Double = 10.0, fuel: String = "0.500", ev: Double? = null): Trip =
    Trip(id, START, START.plusSeconds(1_800), km, BigDecimal(fuel), ev)

private fun cost(id: String, pln: String? = "20.00", source: CostSource = CostSource.REFUEL): TripCost =
    TripCost(id, pln?.let { BigDecimal(it) }, pln?.let { BigDecimal("6.190") }, null, source)

private fun day(temp: String? = "5.0", precip: String? = "0.0", wind: String? = null): DayWeather =
    DayWeather(
        tempMean = temp?.let { BigDecimal(it) },
        precipMm = precip?.let { BigDecimal(it) },
        windMaxKmh = wind?.let { BigDecimal(it) },
    )

/** [count] frosty trips of 10 km; the weather is keyed by trip id, as `app` will key it. */
private fun frosty(count: Int, km: Double = 10.0, fuel: String = "0.700"): Pair<List<Trip>, Map<String, DayWeather>> {
    val trips = (0 until count).map { trip("t$it", km = km, fuel = fuel) }
    return trips to trips.associate { it.id to day(temp = "-4.0") }
}

/** Mirrors backend/tests/test_domain_weather.py - the same names without the `test_` prefix. */
class WeatherOpsTest {

    @Nested
    inner class TemperatureBuckets {
        @Test
        fun `bucket_of_negative_mean_is_frost`() {
            assertEquals(TemperatureBucket.FROST, temperatureBucket(BigDecimal("-0.1")))
            assertEquals(TemperatureBucket.FROST, temperatureBucket(BigDecimal("-9.99")))
        }

        @Test
        fun `bucket_below_minus_ten_is_deep_frost`() {
            assertEquals(TemperatureBucket.FROST, temperatureBucket(BigDecimal("-10")))
            assertEquals(TemperatureBucket.DEEP_FROST, temperatureBucket(BigDecimal("-10.1")))
            assertEquals(TemperatureBucket.DEEP_FROST, temperatureBucket(BigDecimal("-14.6")))
        }

        @Test
        fun `bucket_of_zero_mean_is_cold`() {
            assertEquals(TemperatureBucket.COLD, temperatureBucket(BigDecimal("0")))
            assertEquals(TemperatureBucket.COLD, temperatureBucket(BigDecimal("0.0")))
        }

        @Test
        fun `bucket_boundaries_are_half_open`() {
            assertEquals(TemperatureBucket.COLD, temperatureBucket(BigDecimal("9.999")))
            assertEquals(TemperatureBucket.MILD, temperatureBucket(BigDecimal("10")))
            assertEquals(TemperatureBucket.MILD, temperatureBucket(BigDecimal("19.99")))
            assertEquals(TemperatureBucket.WARM, temperatureBucket(BigDecimal("20")))
            assertNull(temperatureBucket(null))
        }

        @Test
        fun `reference_bucket_is_mild_dry`() {
            assertEquals("mild", REFERENCE_TEMPERATURE_BUCKET.label)
            assertEquals("dry", REFERENCE_PRECIPITATION_BUCKET.label)
            val (mild, mildWeather) = frosty(5, fuel = "0.500")
            val weather = mildWeather.mapValues { day(temp = "14.0") }
            val impact = weatherCostByTemperature(mild, emptyList(), weather)
            assertEquals("mild", impact.referenceBucket)
            assertBd("5.00", impact.referenceLPer100Km)
            assertBd("0.00", impact.buckets.single().deltaLPer100Km)
        }

        @Test
        fun `comparison_none_without_reference`() {
            val (trips, weather) = frosty(6)
            val impact = weatherCostByTemperature(trips, emptyList(), weather, minTrips = 1)
            assertNull(impact.referenceBucket)
            assertNull(impact.referenceLPer100Km)
            assertNull(impact.buckets.single().deltaLPer100Km)
        }
    }

    @Nested
    inner class BucketMeasures {
        @Test
        fun `consumption_is_total_fuel_over_total_km`() {
            val trips = listOf(trip("a", km = 10.0, fuel = "1.000"), trip("b", km = 40.0, fuel = "2.000"))
            val weather = trips.associate { it.id to day(temp = "-3.0") }
            val row = weatherCostByTemperature(trips, emptyList(), weather, minTrips = 1).buckets[0]
            // 3.000 l over 50 km, not the mean of the two trips (which would be 7.50).
            assertBd("6.00", row.lPer100Km)
            assertBd("50.0", row.distanceKm)
            assertBd("3.000", row.fuelL)
        }

        @Test
        fun `cost_none_when_no_trip_in_bucket_has_a_price`() {
            val (trips, weather) = frosty(3)
            val row = weatherCostByTemperature(trips, emptyList(), weather, minTrips = 1).buckets[0]
            assertNull(row.costPln)
            assertNull(row.costPer100Km)
            assertEquals(3, row.tripsWithoutCost)
        }

        @Test
        fun `cost_counts_only_priced_distance`() {
            val trips = listOf(trip("a", km = 10.0), trip("b", km = 40.0))
            val weather = trips.associate { it.id to day(temp = "-3.0") }
            val row = weatherCostByTemperature(trips, listOf(cost("a", "20.00")), weather, minTrips = 1).buckets[0]
            // 20.00 zl of the priced 10 km, not spread over the 50 km of the bucket.
            assertBd("200.00", row.costPer100Km)
            assertBd("20.00", row.costPln)
            assertEquals(1, row.tripsWithoutCost)
        }

        @Test
        fun `bucket_below_minimum_reports_too_few`() {
            val (trips, weather) = frosty(4)
            val impact = weatherCostByTemperature(trips, emptyList(), weather)
            assertTrue(impact.buckets[0].tooFewTrips)
            assertNull(impact.referenceBucket)

            val (enough, enoughWeather) = frosty(5)
            val full = weatherCostByTemperature(enough, emptyList(), enoughWeather)
            assertTrue(!full.buckets[0].tooFewTrips)
        }

        @Test
        fun `rounding_is_half_up`() {
            // 0.6036 l over 8.0 km is exactly 7.545 l/100 km; HALF_UP gives 7.55, not 7.54.
            val trips = listOf(trip("a", km = 8.0, fuel = "0.6036"))
            val weather = trips.associate { it.id to day(temp = "-3.0") }
            val row = weatherCostByTemperature(trips, emptyList(), weather, minTrips = 1).buckets[0]
            assertBd("7.55", row.lPer100Km)
        }

        @Test
        fun `wind_is_reported_but_not_judged`() {
            val trips = listOf(trip("a"), trip("b"))
            val calm = mapOf("a" to day(temp = "-3.0", wind = "4.0"), "b" to day(temp = "-3.0", wind = "11.0"))
            val windy = mapOf("a" to day(temp = "-3.0", wind = "61.0"), "b" to day(temp = "-3.0", wind = "11.0"))
            val calmRow = weatherCostByTemperature(trips, emptyList(), calm, minTrips = 1).buckets[0]
            val windyRow = weatherCostByTemperature(trips, emptyList(), windy, minTrips = 1).buckets[0]
            assertBd("11.0", calmRow.windMaxKmh)
            assertBd("61.0", windyRow.windMaxKmh)
            // A gust does not move the bucket, its consumption or its difference.
            assertEquals("frost", windyRow.bucket)
            assertEquals("frost", calmRow.bucket)
            assertEquals(calmRow.lPer100Km, windyRow.lPer100Km)
            assertEquals(calmRow.tooFewTrips, windyRow.tooFewTrips)
        }
    }

    @Nested
    inner class PrecipitationBuckets {
        @Test
        fun `wet_bucket_needs_half_a_millimetre`() {
            assertEquals(PrecipitationBucket.WET, precipitationBucket(BigDecimal("0.5")))
            assertEquals(PrecipitationBucket.WET, precipitationBucket(BigDecimal("18.4")))
            val trips = listOf(trip("a"))
            val impact = weatherCostByPrecipitation(trips, emptyList(), mapOf("a" to day(temp = "6.0", precip = "0.5")), minTrips = 1)
            assertEquals("wet", impact.buckets[0].bucket)
        }

        @Test
        fun `dry_bucket_below_half_a_millimetre`() {
            assertEquals(PrecipitationBucket.DRY, precipitationBucket(BigDecimal("0.49")))
            assertEquals(PrecipitationBucket.DRY, precipitationBucket(BigDecimal("0")))
            assertNull(precipitationBucket(null))
            val trips = listOf(trip("a"))
            val impact = weatherCostByPrecipitation(trips, emptyList(), mapOf("a" to day(temp = "6.0", precip = "0.4")), minTrips = 1)
            assertEquals("dry", impact.buckets[0].bucket)
            assertEquals("dry", impact.referenceBucket)
        }
    }

    @Nested
    inner class MissingWeather {
        @Test
        fun `null_weather_day_is_not_a_zero`() {
            val trips = listOf(trip("a"), trip("b"))
            val impact = weatherCostByTemperature(
                trips,
                emptyList(),
                mapOf("a" to day(temp = "-4.0"), "b" to DayWeather()),
                minTrips = 1,
            )
            assertEquals(1, impact.buckets.single().trips)
            assertEquals(1, impact.tripsWithoutWeather)
        }

        @Test
        fun `trips_without_weather_are_skipped_and_counted`() {
            val (frostTrips, frostWeather) = frosty(3)
            val trips = frostTrips + listOf(trip("no-weather"), trip("empty-day"))
            val weather = frostWeather + mapOf("empty-day" to DayWeather())
            val impact = weatherCostByTemperature(trips, emptyList(), weather, minTrips = 1)
            assertEquals(5, impact.totalTrips)
            assertEquals(2, impact.tripsWithoutWeather)
            assertEquals(3, impact.buckets.sumOf { it.trips })
        }

        @Test
        fun `empty_inputs_give_no_buckets`() {
            val impact = weatherCostByTemperature(emptyList(), emptyList(), emptyMap())
            assertTrue(impact.buckets.isEmpty())
            assertEquals(0, impact.totalTrips)
            assertEquals(0, impact.tripsWithoutWeather)
            assertNull(impact.referenceBucket)
            assertTrue(weatherCostByPrecipitation(emptyList(), emptyList(), emptyMap()).buckets.isEmpty())
        }
    }
}
