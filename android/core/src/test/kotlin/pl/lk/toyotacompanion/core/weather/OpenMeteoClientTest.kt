package pl.lk.toyotacompanion.core.weather

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

private val TODAY: LocalDate = LocalDate.parse("2026-11-01")

private fun reply(code: Int, body: String, vararg headers: Pair<String, String>) =
    MockResponse.Builder().code(code).apply { headers.forEach { addHeader(it.first, it.second) } }.body(body).build()

private const val TWO_DAYS = """{"latitude":54.37,"longitude":18.63,"daily":{
"time":["2026-10-30","2026-10-31"],
"temperature_2m_mean":[8.5,null],"temperature_2m_min":[5.0,null],"temperature_2m_max":[11.0,null],
"precipitation_sum":[0.0,2.4],"precipitation_hours":[0,5],"wind_speed_10m_max":[20.5,null]}}"""

/** docs/SPEC.md §23: what leaves the app, what comes back, and how failures look. */
class OpenMeteoClientTest {
    private fun <T> withServer(block: suspend (MockWebServer, OpenMeteoClient) -> T): T = MockWebServer().use { server ->
        server.start()
        val client = OpenMeteoClient(OkHttpClient(), baseUrl = server.url("/").toString(), today = { TODAY })
        runBlocking { block(server, client) }
    }

    @Test
    fun `the request carries only the rounded position, dates and variables`() = withServer { server, client ->
        server.enqueue(reply(200, TWO_DAYS))
        client.daily(54.37249, 18.63851, LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-31"))
        val request = server.takeRequest()
        val url = request.url
        assertEquals("/v1/archive", url.encodedPath)
        assertEquals("54.37", url.queryParameter("latitude"))
        assertEquals("18.64", url.queryParameter("longitude"))
        assertEquals("2026-10-30", url.queryParameter("start_date"))
        assertEquals("2026-10-31", url.queryParameter("end_date"))
        assertEquals(OpenMeteoClient.DAILY_VARIABLES, url.queryParameter("daily"))
        val allowed = setOf("latitude", "longitude", "start_date", "end_date", "daily", "timezone", "timeformat")
        assertEquals(allowed, url.queryParameterNames)
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
        assertEquals("toyota-companion", request.headers["User-Agent"])
    }

    @Test
    fun `values parse and a missing value is null, never zero`() = withServer { server, client ->
        server.enqueue(reply(200, TWO_DAYS))
        val days = client.daily(54.37, 18.63, LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-31"))
        assertEquals(2, days.size)
        assertEquals(WeatherDay(LocalDate.parse("2026-10-30"), 8.5, 5.0, 11.0, 0.0, 0.0, 20.5), days[0])
        assertNull(days[1].tempMeanC)
        assertNull(days[1].windMaxKmh)
        assertEquals(2.4, days[1].precipitationMm)
        assertEquals(5.0, days[1].precipitationHours)
    }

    @Test
    fun `the end is cut to yesterday and a range in the future needs no request`() = withServer { server, client ->
        server.enqueue(reply(200, TWO_DAYS))
        client.daily(54.37, 18.63, LocalDate.parse("2026-10-30"), LocalDate.parse("2026-11-05"))
        assertEquals("2026-10-31", server.takeRequest().url.queryParameter("end_date"))
        assertEquals(emptyList(), client.daily(54.37, 18.63, TODAY, TODAY))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 is a rate limit with its Retry-After`() = withServer { server, client ->
        server.enqueue(reply(429, """{"error":true,"reason":"secret-body"}""", "Retry-After" to "30"))
        val e = assertFailsWith<WeatherFetchException> {
            client.daily(54.37, 18.63, LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-31"))
        }
        assertEquals(WeatherErrorKind.RATE_LIMIT, e.kind)
        assertEquals(30L, e.retryAfterSeconds)
        assertFalse(e.message.orEmpty().contains("secret-body"))
    }

    @Test
    fun `400 and 503 are API failures without the body`() = withServer { server, client ->
        for (code in listOf(400, 503)) {
            server.enqueue(reply(code, """{"error":true,"reason":"latitude 54.37 is wrong"}"""))
            val e = assertFailsWith<WeatherFetchException> {
                client.daily(54.37, 18.63, LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-31"))
            }
            assertEquals(WeatherErrorKind.API, e.kind)
            assertTrue(e.message.orEmpty().contains(code.toString()))
            assertFalse(e.message.orEmpty().contains("54.37"))
        }
    }

    @Test
    fun `an array answer or garbage is a data failure`() = withServer { server, client ->
        for (body in listOf("""[{"daily":{"time":[]}}]""", "not json", """{"daily":{}}""", """{"daily":{"time":["x"]}}""")) {
            server.enqueue(reply(200, body))
            val e = assertFailsWith<WeatherFetchException> {
                client.daily(54.37, 18.63, LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-31"))
            }
            assertEquals(WeatherErrorKind.DATA, e.kind)
        }
    }

    @Test
    fun `no connection is a network failure that names no host`() {
        val client = OpenMeteoClient(OkHttpClient(), baseUrl = "http://127.0.0.1:1", today = { TODAY })
        val e = assertFailsWith<WeatherFetchException> {
            runBlocking { client.daily(54.37, 18.63, LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-31")) }
        }
        assertEquals(WeatherErrorKind.NETWORK, e.kind)
        assertFalse(e.message.orEmpty().contains("127.0.0.1"))
    }

    @Test
    fun `a position that is not finite is refused before any request`() = withServer { server, client ->
        assertFailsWith<WeatherFetchException> {
            client.daily(Double.NaN, 18.63, LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-31"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rounding is half up with a dot`() {
        assertEquals("54.37", OpenMeteoClient.rounded(54.365))
        assertEquals("-0.01", OpenMeteoClient.rounded(-0.005))
        assertEquals("18.00", OpenMeteoClient.rounded(18.0))
    }
}
