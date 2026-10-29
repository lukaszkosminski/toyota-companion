package pl.lk.toyotacompanion.core.sync

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import pl.lk.toyotacompanion.core.toyota.InMemoryTokenStore
import pl.lk.toyotacompanion.core.toyota.TokenInfo
import pl.lk.toyotacompanion.core.toyota.ToyotaApi
import pl.lk.toyotacompanion.core.toyota.ToyotaController
import pl.lk.toyotacompanion.core.toyota.ToyotaUrls

private fun fixture(name: String): String =
    requireNotNull(ToyotaApiGatewayTest::class.java.getResourceAsStream("/toyota/$name"))
        .reader(Charsets.UTF_8).readText()

private fun okRes(body: String) = MockResponse.Builder().code(200)
    .addHeader("Content-Type", "application/json").body(body).build()

private const val TEST_VIN = "VINTEST0000000001"
private val SYNC_AT: Instant = Instant.parse("2026-10-29T08:00:00Z")

/**
 * The production gateway on a mock server: proves the wire fixtures really
 * become archive records, including pages and the telemetry-empty case.
 */
class ToyotaApiGatewayTest {

    private fun gateway(server: MockWebServer): ToyotaApiGateway {
        val urls = ToyotaUrls(
            apiBase = server.url("/").toString().trimEnd('/'),
            authenticate = server.url("/authenticate").toString(),
            authorize = server.url("/authorize").toString(),
            accessToken = server.url("/token").toString(),
        )
        val store = InMemoryTokenStore().apply {
            save("u", TokenInfo("ACCESS", "REFRESH", "11111111-2222-3333-4444-555555555555", Instant.now().plusSeconds(600)))
        }
        return ToyotaApiGateway(ToyotaApi(ToyotaController("u", "p", okhttp3.OkHttpClient(), urls, store)), now = { SYNC_AT })
    }

    @Test
    fun `vehicles become archived records`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(okRes(fixture("v2_vehicleguid.json")))

            val fleet = gateway(server).vehicles()

            assertEquals(1, fleet.size)
            assertEquals("JT***************", fleet.single().vin)
            assertEquals("RAV4 Plug-in MY21", fleet.single().modelName)
            assertEquals(SYNC_AT, fleet.single().firstSeen)
        }
    }

    @Test
    fun `telemetry becomes one fuel reading`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(okRes(fixture("v3_telemetry.json")))

            val reading = requireNotNull(gateway(server).telemetry(TEST_VIN))

            assertEquals(99, reading.levelPct)
            assertEquals(30_420.0, reading.odometerKm)
            assertEquals(Instant.parse("2023-11-28T09:45:40Z"), reading.ts)
        }
    }

    @Test
    fun `telemetry without fuel level becomes null`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(
                okRes("""{"payload":{"odometer":{"value":100,"unit":"km"},"timestamp":"2026-10-29T07:00:00Z"}}"""),
            )

            assertNull(gateway(server).telemetry(TEST_VIN))
        }
    }

    @Test
    fun `trips become archive records across pages`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(okRes(fixture("v1_trips.json")))
            server.enqueue(okRes(fixture("trips_page2.json")))

            val records = gateway(server).trips(TEST_VIN, LocalDate.of(2023, 9, 1), LocalDate.of(2023, 11, 30))

            assertEquals(2, server.requestCount)
            assertEquals(10, records.size)
            assertTrue(records.all { it.vin == TEST_VIN })
            assertTrue(records.all { it.syncedAt == SYNC_AT })
            assertTrue(records.all { it.trip.fuelL.scale() == 3 })
            assertTrue(records.any { it.scoreGlobal != null })
            assertTrue(records.any { it.hdcEvDistanceM != null })
        }
    }
}
