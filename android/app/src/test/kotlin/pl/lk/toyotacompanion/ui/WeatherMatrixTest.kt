package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.DayWeather
import pl.lk.toyotacompanion.core.domain.TemperatureBucket
import pl.lk.toyotacompanion.core.domain.Trip

private val AT: Instant = Instant.parse("2026-10-20T08:00:00Z")

private fun ride(id: String, speed: Double?, l100: Double = 5.0, km: Double = 10.0, ev: Double? = null): StoredTrip {
    val fuel = BigDecimal(km * l100 / 100.0).setScale(3, java.math.RoundingMode.HALF_UP)
    return StoredTrip(TripRecord(Trip(id, AT, AT.plusSeconds(1_800), km, fuel, ev), "VINAAA111", averageSpeed = speed), null)
}

private fun day(mean: Double?) = DayWeather(tempMean = mean?.let { BigDecimal(it.toString()) })

private fun many(prefix: String, n: Int, speed: Double, l100: Double) = (1..n).map { ride("$prefix$it", speed, l100) }

/** docs/SPEC.md §28: the table of speed bands by the temperature of the day. */
class WeatherMatrixTest {
    @Test
    fun `a trip lands in the cell of its speed band and its temperature bucket`() {
        val trips = many("a", 6, 10.0, 6.0) + many("b", 6, 10.0, 5.0) + many("c", 6, 30.0, 4.0)
        val weather = trips.associate { it.record.id to day(if (it.record.id.startsWith("a")) -3.0 else 12.0) }
        val rows = speedWeatherMatrix(trips, weather)
        assertEquals(listOf(TrafficClass.SLOW, TrafficClass.FLOWING), rows.map { it.speed })
        val slow = rows.first()
        assertEquals(BigDecimal("6.00"), slow.cells.getValue(TemperatureBucket.FROST).lPer100)
        assertEquals(BigDecimal("5.00"), slow.cells.getValue(TemperatureBucket.MILD).lPer100)
        assertEquals(setOf(TemperatureBucket.FROST, TemperatureBucket.MILD), slow.cells.keys)
        assertEquals(BigDecimal("1.00"), slow.frostSurcharge())
    }

    @Test
    fun `a cell under five trips shows the count but gives no surcharge`() {
        val trips = many("a", 4, 10.0, 6.0) + many("b", 6, 10.0, 5.0)
        val weather = trips.associate { it.record.id to day(if (it.record.id.startsWith("a")) -3.0 else 12.0) }
        val slow = speedWeatherMatrix(trips, weather).single()
        assertEquals(false, slow.cells.getValue(TemperatureBucket.FROST).enough)
        assertNull(slow.frostSurcharge())
        assertEquals("4 tras – za mało", WeatherFormatting.cellText(slow.cells.getValue(TemperatureBucket.FROST)))
        assertEquals("5,00 l (6)", WeatherFormatting.cellText(slow.cells.getValue(TemperatureBucket.MILD)))
        assertEquals("–", WeatherFormatting.cellText(null))
    }

    @Test
    fun `trips without weather, without a temperature or outside the speed bands are left out`() {
        val trips = listOf(ride("a", 10.0), ride("b", 10.0), ride("c", null), ride("d", 10.0, km = 1.0), ride("e", 10.0))
        val weather = mapOf("a" to day(5.0), "b" to day(null), "c" to day(5.0), "d" to day(5.0))
        val rows = speedWeatherMatrix(trips, weather)
        assertEquals(1, rows.single().cells.getValue(TemperatureBucket.COLD).trips)
    }

    @Test
    fun `no trips with weather give an empty table`() {
        assertEquals(emptyList(), speedWeatherMatrix(many("a", 8, 10.0, 5.0), emptyMap()))
    }

    @Test
    fun `bucket edges follow the weather buckets`() {
        val trips = listOf("a", "b", "c", "d", "e").map { ride(it, 10.0) }
        val weather = mapOf("a" to day(-10.1), "b" to day(-0.1), "c" to day(0.0), "d" to day(10.0), "e" to day(20.0))
        val cells = speedWeatherMatrix(trips, weather, minTrips = 1).single().cells
        assertEquals(TemperatureBucket.entries.toSet(), cells.keys)
        assertTrue(cells.values.all { it.trips == 1 })
    }

    @Test
    fun `the electric share of a cell is capped by the trips own distance`() {
        val trips = listOf(ride("a", 10.0, km = 10.0, ev = 30.0), ride("b", 10.0, km = 10.0, ev = 0.0))
        val cell = speedWeatherMatrix(trips, trips.associate { it.record.id to day(5.0) }, minTrips = 1).single()
            .cells.getValue(TemperatureBucket.COLD)
        assertEquals(BigDecimal("50"), cell.evSharePct)
    }

    @Test
    fun `the row text names the buckets and the frost line has a sign`() {
        val trips = many("a", 6, 10.0, 6.0) + many("b", 6, 10.0, 5.0)
        val weather = trips.associate { it.record.id to day(if (it.record.id.startsWith("a")) -3.0 else 12.0) }
        val row = speedWeatherMatrix(trips, weather).single()
        assertEquals("silny mróz – · mróz 6,00 l (6) · chłodno – · łagodnie 5,00 l (6) · ciepło –", WeatherFormatting.rowText(row))
        assertEquals("Zimą przy tej prędkości: +1,00 l/100 km (mróz wobec łagodnie)", WeatherFormatting.frostLine(row))
    }
}
