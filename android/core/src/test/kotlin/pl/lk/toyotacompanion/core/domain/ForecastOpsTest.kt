@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertBd(expected: String, actual: BigDecimal?, label: String = "value") {
    val e = BigDecimal(expected)
    val a = requireNotNull(actual) { "$label is null, expected $expected" }
    assertTrue(e.compareTo(a) == 0) { "$label: expected $expected but was $a" }
}

private val NOW: Instant = Instant.parse("2026-10-31T12:00:00Z")

private fun reading(level: Int = 50, hoursAgo: Double = 0.0) =
    FuelReading(NOW.minusSeconds((hoursAgo * 3600).toLong()), level, 1000.0)

private fun trip(id: String, daysAgo: Double, km: Double = 20.0, fuel: String = "1.000"): Trip {
    val start = NOW.minusSeconds((daysAgo * 86_400).toLong())
    return Trip(id, start, start.plusSeconds(1_800), km, BigDecimal(fuel), null)
}

private fun monthOfTrips(): List<Trip> = (0 until 28).map { trip("t$it", it + 0.5) }

/** Mirrors backend/tests/test_domain_forecast.py - the same names without the `test_` prefix. */
class ForecastOpsTest {

    @Nested
    inner class RefuelForecastTests {
        @Test
        fun `forecast_from_level_and_habits`() {
            val forecast = assertNotNull(refuelForecast(reading(50), monthOfTrips(), NOW))
            assertBd("21.500", forecast.litersLeft)
            assertBd("50.0", forecast.levelPct)
            assertBd("5.00", forecast.lPer100Km)
            assertBd("20.0", forecast.dailyKm)
            assertBd("344.0", forecast.kmToReserve)
            assertBd("17.2", forecast.daysToReserve)
        }

        @Test
        fun `fuel_used_after_the_reading_is_subtracted`() {
            val trips = monthOfTrips() + trip("after", 0.1, km = 40.0, fuel = "2.000")
            val forecast = assertNotNull(refuelForecast(reading(50, hoursAgo = 11.0), trips, NOW))
            assertBd("19.500", forecast.litersLeft)
        }

        @Test
        fun `level_below_reserve_gives_zero_days`() {
            val forecast = assertNotNull(refuelForecast(reading(8), monthOfTrips(), NOW))
            assertBd("0.0", forecast.kmToReserve)
            assertBd("0.0", forecast.daysToReserve)
        }

        @Test
        fun `fuel_never_goes_below_zero`() {
            val trips = monthOfTrips() + trip("big", 0.1, fuel = "50.000")
            val forecast = assertNotNull(refuelForecast(reading(10, hoursAgo = 12.0), trips, NOW))
            assertBd("0.000", forecast.litersLeft)
        }

        @Test
        fun `young_archive_uses_its_own_days`() {
            val forecast = assertNotNull(refuelForecast(reading(50), listOf(trip("a", 1.5), trip("b", 0.5)), NOW))
            assertBd("20.0", forecast.dailyKm)
        }

        @Test
        fun `trips_older_than_the_window_are_ignored`() {
            val trips = monthOfTrips() + trip("old", 40.0, km = 1000.0, fuel = "100.000")
            val forecast = assertNotNull(refuelForecast(reading(50), trips, NOW))
            assertBd("5.00", forecast.lPer100Km)
        }

        @Test
        fun `no_reading_no_forecast`() {
            assertNull(refuelForecast(null, monthOfTrips(), NOW))
        }

        @Test
        fun `no_trips_in_window_no_forecast`() {
            assertNull(refuelForecast(reading(50), listOf(trip("old", 40.0)), NOW))
        }

        @Test
        fun `trips_on_the_window_edges_count`() {
            val trips = listOf(trip("edge", 28.0, km = 40.0, fuel = "2.000"), trip("now", 0.0, km = 40.0, fuel = "2.000"))
            val forecast = assertNotNull(refuelForecast(reading(50), trips, NOW))
            assertBd("2.9", forecast.dailyKm)
        }

        @Test
        fun `broken_distance_is_ignored`() {
            val trips = monthOfTrips() + trip("nan", 1.0, km = Double.NaN) + trip("inf", 1.0, km = Double.POSITIVE_INFINITY)
            val forecast = assertNotNull(refuelForecast(reading(50), trips, NOW))
            assertBd("5.00", forecast.lPer100Km)
        }

        @Test
        fun `zero_fuel_no_forecast`() {
            assertNull(refuelForecast(reading(50), listOf(trip("ev", 1.0, fuel = "0.000")), NOW))
        }
    }
}
