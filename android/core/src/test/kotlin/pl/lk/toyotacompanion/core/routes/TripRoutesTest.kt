package pl.lk.toyotacompanion.core.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import pl.lk.toyotacompanion.core.toyota.InMemoryTokenStore
import pl.lk.toyotacompanion.core.toyota.TokenInfo
import pl.lk.toyotacompanion.core.toyota.ToyotaApi
import pl.lk.toyotacompanion.core.toyota.ToyotaController
import pl.lk.toyotacompanion.core.toyota.ToyotaUrls
import pl.lk.toyotacompanion.core.toyota.dto.TripDetailResponse
import java.time.Instant

private const val DETAIL = """{"payload":{"id":"t1","route":[
 {"lat":54.37201,"lon":18.63801,"isEv":true,"overspeed":false,"highway":false,"mode":0,"indexInPoints":0},
 {"lat":54.37301,"lon":18.63901,"isEv":false},
 {"lat":54.37401,"lon":18.64001},
 {"lat":null,"lon":18.64},
 {"lat":95.0,"lon":18.64},
 {"lat":54.4,"lon":999.0}]}}"""

class TripRoutesTest {
    @Test
    fun `usable points are kept in order and broken ones are dropped`() {
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<TripDetailResponse>(DETAIL).payload!!
        val points = routePointsOf(dto.route)
        assertEquals(
            listOf(
                TripRoutePoint(54.37201, 18.63801, true),
                TripRoutePoint(54.37301, 18.63901, false),
                TripRoutePoint(54.37401, 18.64001, null),
            ),
            points,
        )
        assertEquals(emptyList(), routePointsOf(null))
    }

    @Test
    fun `a route round-trips through its stored text`() {
        val points = listOf(TripRoutePoint(54.37201, 18.63801, true), TripRoutePoint(54.0, 18.5, false), TripRoutePoint(-33.8, 151.2, null))
        assertEquals(points, decodeRoute(encodeRoute(points)))
        assertEquals("", encodeRoute(emptyList()))
        assertEquals(emptyList(), decodeRoute(""))
    }

    @Test
    fun `a damaged piece of the stored text is skipped`() {
        assertEquals(listOf(TripRoutePoint(54.1, 18.2, true)), decodeRoute("54.10000,18.20000,1;garbage;54.3,x,0;1,2"))
    }

    @Test
    fun `the gateway asks for the detail of one trip with the vin header and maps its route`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(DETAIL).build())
            val store = InMemoryTokenStore().apply {
                save("u", TokenInfo("ACCESS", "REFRESH", "11111111-2222-3333-4444-555555555555", Instant.now().plusSeconds(600)))
            }
            val urls = ToyotaUrls(
                apiBase = server.url("/").toString().trimEnd('/'),
                authenticate = server.url("/authenticate").toString(),
                authorize = server.url("/authorize").toString(),
                accessToken = server.url("/token").toString(),
            )
            val gateway = ToyotaTripRouteGateway(ToyotaApi(ToyotaController("u", "p", OkHttpClient(), urls, store)))
            val points = runBlocking { gateway.route("VINAAA111", "t1") }
            assertEquals(3, points.size)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/trips/t1", request.url.encodedPath)
            assertEquals("VINAAA111", request.headers["vin"])
            assertNull(request.url.queryParameter("route"))
        }
    }
}
