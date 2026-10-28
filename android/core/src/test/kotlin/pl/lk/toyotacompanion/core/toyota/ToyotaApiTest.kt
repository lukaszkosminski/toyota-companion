@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.toyota

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import pl.lk.toyotacompanion.core.toyota.dto.guid
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.toyota.dto.HdcDto
import pl.lk.toyotacompanion.core.toyota.dto.TelemetryDto
import pl.lk.toyotacompanion.core.toyota.dto.TripDto
import pl.lk.toyotacompanion.core.toyota.dto.TripSummaryDto
import pl.lk.toyotacompanion.core.toyota.dto.UnitValueDto

private fun fixture(name: String): String =
    requireNotNull(ToyotaApiTest::class.java.getResourceAsStream("/toyota/$name"))
        .reader(Charsets.UTF_8).readText()

private fun seededTopStore() = InMemoryTokenStore().apply {
    save(
        "u",
        TokenInfo("ACCESS", "REFRESH", "11111111-2222-3333-4444-555555555555", Instant.now().plusSeconds(600)),
    )
}

private fun okRes(body: String) = MockResponse.Builder().code(200)
    .addHeader("Content-Type", "application/json").body(body).build()

class ToyotaApiTest {

    private class ApiCtx(val server: MockWebServer) {
        val api: ToyotaApi
        init {
            val urls = ToyotaUrls(
                apiBase = server.url("/").toString().trimEnd('/'),
                authenticate = server.url("/authenticate").toString(),
                authorize = server.url("/authorize").toString(),
                accessToken = server.url("/token").toString(),
            )
            api = ToyotaApi(ToyotaController("u", "p", okhttp3.OkHttpClient(), urls, seededTopStore()))
        }
    }

        @Test
    fun `vehicles_returns_guid_and_vin`() = runBlocking {
        MockWebServer().apply { start() }.use { mock ->
            ApiCtx(mock).let { ctx ->
                ctx.server.enqueue(okRes(fixture("v2_vehicleguid.json")))
                val vehicles = ctx.api.getVehicles()
                assertTrue(vehicles.isNotEmpty())
                val first = vehicles.first()
                assertTrue(first.vin!!.startsWith("JT"))
                assertEquals("11111111-2222-3333-4444-555555555555", first.guid)
                val req = ctx.server.takeRequest()
                assertEquals("/v2/vehicle/guid", req.url.encodedPath)
            }
        }
    }

    @Test
    fun `telemetry maps fuel level and odometer`() = runBlocking {
        MockWebServer().apply { start() }.use { mock ->
            ApiCtx(mock).let { ctx ->
                ctx.server.enqueue(okRes(fixture("v3_telemetry.json")))
                val telemetryDto = ctx.api.getTelemetry("VIN")
                val reading = ToyotaMapper.toFuelReading(telemetryDto)!!
                assertEquals(99, reading.levelPct)
                assertEquals(30420.0, reading.odometerKm, 1e-9)
                assertEquals(Instant.parse("2023-11-28T09:45:40Z"), reading.ts)
                val req = ctx.server.takeRequest()
                assertEquals("/v3/telemetry", req.url.encodedPath)
                assertEquals("VIN", req.headers["vin"])
            }
        }
    }

    @Test
    fun `trips_pagination_follows next offset to end`() = runBlocking {
        MockWebServer().apply { start() }.use { mock ->
            ApiCtx(mock).let { ctx ->
                ctx.server.enqueue(okRes(fixture("v1_trips.json")))
                ctx.server.enqueue(okRes(fixture("trips_page2.json")))
                val trips = ctx.api.fetchAllTrips("VIN", LocalDate.of(2023, 9, 1), LocalDate.of(2023, 11, 30))
                assertEquals(2, mock.requestCount)
                val first = mock.takeRequest()
                assertTrue(first.url.encodedPath.contains("/v1/trips"))
                assertEquals("0", first.url.queryParameter("offset"))
                val second = mock.takeRequest()
                assertEquals("5", second.url.queryParameter("offset"))
                assertTrue(trips.isNotEmpty())
            }
        }
    }

    @Test
    fun `trip detail endpoint used for single trip`() = runBlocking {
        MockWebServer().apply { start() }.use { mock ->
            ApiCtx(mock).let { ctx ->
                ctx.server.enqueue(okRes(fixture("trip_detail.json")))
                val dto = ctx.api.getTripDetails("VIN", "ffe77c0a-0b33-4496-971a-c767fbd7aba5")
                assertEquals("ffe77c0a-0b33-4496-971a-c767fbd7aba5", dto.id)
                val req = mock.takeRequest()
                assertTrue(req.url.encodedPath.endsWith("/v1/trips/ffe77c0a-0b33-4496-971a-c767fbd7aba5"))
            }
        }
    }
}

class ToyotaMapperTest {

    private fun tripDto(fuelMl: Double, lengthM: Long, evM: Long? = 13170) = TripDto(
        id = "t-1",
        summary = TripSummaryDto(
            length = lengthM,
            fuelConsumption = fuelMl,
            startTs = "2023-11-21T19:55:13Z",
            endTs = "2023-11-21T20:09:11Z",
        ),
        hdc = evM?.let { HdcDto(evDistance = it) },
    )

    @Test
    fun `trip fuel consumption ml becomes liters scale 3`() {
        val trip = ToyotaMapper.toDomainTrip(tripDto(fuelMl = 226.0, lengthM = 15215))!!
        assertEquals(0, java.math.BigDecimal("0.226").compareTo(trip.fuelL))
        assertEquals(3, trip.fuelL.scale())
    }

    @Test
    fun `trip length meters to km`() {
        val trip = ToyotaMapper.toDomainTrip(tripDto(fuelMl = 226.0, lengthM = 15215))!!
        assertEquals(15.215, trip.distanceKm, 1e-9)
    }

    @Test
    fun `trip hdc ev distance or null`() {
        val withHdc = ToyotaMapper.toDomainTrip(tripDto(226.0, 15215, evM = 13170))!!
        assertEquals(13.17, withHdc.evDistanceKm!!, 1e-9)
        val without = ToyotaMapper.toDomainTrip(tripDto(226.0, 15215, evM = null))!!
        kotlin.test.assertNull(without.evDistanceKm)
    }

    @Test
    fun `integration average fuel consumed matches pytoyoda 1 485`() {
        // tests/integration_tests/test_authentication.py:170 — 1.485 l/100km
        val trip = ToyotaMapper.toDomainTrip(tripDto(fuelMl = 226.0, lengthM = 15215))!!
        val avg = trip.fuelL
            .divide(java.math.BigDecimal.valueOf(trip.distanceKm), 10, java.math.RoundingMode.HALF_UP)
            .multiply(java.math.BigDecimal(100))
            .setScale(3, java.math.RoundingMode.HALF_UP)
        assertEquals(0, java.math.BigDecimal("1.485").compareTo(avg))
    }

    @Test
    fun `telemetry odometer miles converted to km`() {
        val dto = TelemetryDto(
            fuelLevel = 50,
            timestamp = "2023-11-28T09:45:40Z",
            odometer = UnitValueDto(value = 100.0, unit = "mi"),
        )
        val reading = ToyotaMapper.toFuelReading(dto)!!
        assertEquals(100 * 1.60934, reading.odometerKm, 1e-9)
    }

    @Test
    fun `incomplete telemetry maps to null`() {
        kotlin.test.assertNull(ToyotaMapper.toFuelReading(TelemetryDto(fuelLevel = null)))
        kotlin.test.assertNull(
            ToyotaMapper.toFuelReading(TelemetryDto(fuelLevel = 10, timestamp = "nonsense")),
        )
    }
}
