@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.toyota

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

private fun resourceText(name: String): String =
    requireNotNull(ToyotaControllerTest::class.java.getResourceAsStream("/toyota/$name"))
        .reader(Charsets.UTF_8).readText()

private fun jsonResponse(name: String, status: Int = 200): MockResponse =
    MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json")
        .body(resourceText(name))
        .build()

private val USER = "mock@example.com"
private val PASS = "mock-password"

class ToyotaControllerTest {

    private fun validToken(access: String = "ACCESS-PRE", refresh: String = "REFRESH-PRE") = TokenInfo(
        accessToken = access,
        refreshToken = refresh,
        uuid = "11111111-2222-3333-4444-555555555555",
        expiration = Instant.now().plusSeconds(3000),
    )

    private fun expiredToken(refresh: String = "REFRESH-PRE") = TokenInfo(
        accessToken = "ACCESS-STALE",
        refreshToken = refresh,
        uuid = "11111111-2222-3333-4444-555555555555",
        expiration = Instant.now().minusSeconds(60),
    )

    private fun enqueueLoginFlow(server: MockWebServer, code: String = "abc") {
        server.enqueue(jsonResponse("auth_step_username.json"))
        server.enqueue(jsonResponse("auth_step_password.json"))
        server.enqueue(jsonResponse("auth_step_tokenid.json"))
        server.enqueue(
            MockResponse.Builder().code(302)
                .addHeader("Location", "com.toyota.oneapp:/oauth2Callback?code=$code")
                .build(),
        )
        server.enqueue(jsonResponse("tokens_success.json"))
    }

    @Test
    fun `login_success_full_flow`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            enqueueLoginFlow(server)
            val store = InMemoryTokenStore()
            ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server), tokenStore = store).login()

            val token = requireNotNull(store.load(USER))
            assertEquals("ACCESS-MOCK-1", token.accessToken)
            assertEquals("REFRESH-MOCK-1", token.refreshToken)
            assertEquals("11111111-2222-3333-4444-555555555555", token.uuid)
            assertTrue(token.isValidAt(Instant.now()))

            // 3 callback POSTs + authorize GET + token POST
            assertEquals(5, server.requestCount)
            val first = server.takeRequest()
            assertEquals("/authenticate", first.url.encodedPath)
            assertTrue(requireNotNull(first.body).utf8() in listOf("{}", "{}\n"))
            val second = server.takeRequest()
            assertTrue(requireNotNull(second.body).utf8().contains("mock@example.com")) { "username filled" }
            val third = server.takeRequest()
            assertTrue(requireNotNull(third.body).utf8().contains("mock-password")) { "password filled" }
            val auth = server.takeRequest()
            assertEquals("GET", auth.method)
            assertEquals("iPlanetDirectoryPro=MOCK-TOKEN-ID", auth.headers["cookie"])
            val tokenReq = server.takeRequest()
            assertTrue(requireNotNull(tokenReq.body).utf8().contains("code=abc")) { "auth code forwarded" }
        }
    }

    @Test
    fun `authorization_code_is_url_decoded`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(jsonResponse("auth_step_username.json"))
            server.enqueue(jsonResponse("auth_step_password.json"))
            server.enqueue(jsonResponse("auth_step_tokenid.json"))
            server.enqueue(
                MockResponse.Builder().code(302)
                    .addHeader("Location", "com.toyota.oneapp:/oauth2Callback?code=a%2Bb")
                    .build(),
            )
            server.enqueue(jsonResponse("tokens_success.json"))
            ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server)).login()

            // ostatnim żądaniem jest POST /token; '+' musi zostać zakodowany z powrotem jako %2B
            repeat(4) { server.takeRequest() }
            val last = server.takeRequest()
            assertEquals("/token", last.url.encodedPath)
            assertTrue(requireNotNull(last.body).utf8().contains("code=a%2Bb")) { "plus survives decode+encode" }
        }
    }

    @Test
    fun `login_invalid_password_raises_login_exception`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(jsonResponse("auth_step_username.json"))
            server.enqueue(MockResponse.Builder().code(401).body("Invalid credentials").build())
            val ex = assertFailsWith<ToyotaLoginException> {
                ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server)).login()
            }
            assertTrue(ex.message!!.contains("401"))
        }
    }

    @Test
    fun `login_user_not_found_raises_invalid_username`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(jsonResponse("auth_user_not_found.json"))
            assertFailsWith<ToyotaInvalidUsernameException> {
                ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server)).login()
            }
        }
    }

    @Test
    fun `login_authorization_without_redirect_raises`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(jsonResponse("auth_step_username.json"))
            server.enqueue(jsonResponse("auth_step_password.json"))
            server.enqueue(jsonResponse("auth_step_tokenid.json"))
            server.enqueue(MockResponse.Builder().code(200).body("no redirect").build())
            assertFailsWith<ToyotaLoginException> {
                ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server)).login()
            }
        }
    }

    @Test
    fun `login_token_id_never_arrives_raises`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            repeat(10) { server.enqueue(jsonResponse("auth_step_username.json")) }
            assertFailsWith<ToyotaLoginException> {
                ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server)).login()
            }
            assertEquals(10, server.requestCount)
        }
    }

    @Test
    fun `expired_token_refreshes_via_refresh_token`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val store = InMemoryTokenStore()
            store.save(USER, expiredToken(refresh = "REFRESH-MOCK-1"))
            server.enqueue(jsonResponse("tokens_refreshed.json"))
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())

            ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server), tokenStore = store)
                .request("GET", "/v3/telemetry")

            val refreshReq = server.takeRequest()
            assertEquals("/token", refreshReq.url.encodedPath)
            assertTrue(requireNotNull(refreshReq.body).utf8().contains("grant_type=refresh_token"))
            val dataReq = server.takeRequest()
            assertEquals("Bearer ACCESS-MOCK-2", dataReq.headers["authorization"])
        }
    }

    @Test
    fun `refresh_failure_falls_back_to_full_login`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val store = InMemoryTokenStore()
            store.save(USER, expiredToken(refresh = "REFRESH-MOCK-1"))
            server.enqueue(MockResponse.Builder().code(401).body("expired").build())
            enqueueLoginFlow(server)
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())

            ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server), tokenStore = store)
                .request("GET", "/v3/telemetry")

            // kolejnosc: 1 refresh POST + 3 callback POST + authorize GET + token POST = 6
            repeat(6) { server.takeRequest() }
            val dataReq = server.takeRequest()
            assertEquals("Bearer ACCESS-MOCK-1", dataReq.headers["authorization"])
        }
    }

    @Test
    fun `login_reuses_valid_token_no_network`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val store = InMemoryTokenStore()
            store.save(USER, validToken())
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server), tokenStore = store)
                .request("GET", "/v3/telemetry")
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `update_tokens_missing_field_raises`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(jsonResponse("auth_step_username.json"))
            server.enqueue(jsonResponse("auth_step_password.json"))
            server.enqueue(jsonResponse("auth_step_tokenid.json"))
            server.enqueue(
                MockResponse.Builder().code(302)
                    .addHeader("Location", "com.toyota.oneapp:/oauth2Callback?code=abc").build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body("""{"access_token":"A","refresh_token":"R","expires_in":100}""")
                    .build(),
            )
            val ex = assertFailsWith<ToyotaLoginException> {
                ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server)).login()
            }
            assertTrue(ex.message!!.contains("id_token"))
        }
    }

    // ---------------- retry / backoff ----------------

    @Test
    fun `request_retries_on_429_then_succeeds`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val sleeps = mutableListOf<Long>()
            val store = InMemoryTokenStore()
            store.save(USER, validToken())
            server.enqueue(MockResponse.Builder().code(429).body("slow down").build())
            server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
            val body = ToyotaController(
                USER, PASS, OkHttpClient(), urls = urlsFor(server),
                tokenStore = store, sleeper = { sleeps += it },
            ).request("GET", "/v3/telemetry")
            assertTrue(body.contains("ok"))
            assertEquals(listOf(2L), sleeps)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `request_gives_up_after_four_attempts_on_429`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val sleeps = mutableListOf<Long>()
            val store = InMemoryTokenStore()
            store.save(USER, validToken())
            repeat(4) { server.enqueue(MockResponse.Builder().code(429).body("slow").build()) }
            val ex = assertFailsWith<ToyotaApiException> {
                ToyotaController(
                    USER, PASS, OkHttpClient(), urls = urlsFor(server),
                    tokenStore = store, sleeper = { sleeps += it },
                ).request("GET", "/v3/telemetry")
            }
            assertEquals(429, ex.statusCode)
            assertEquals(listOf(2L, 4L, 8L), sleeps)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `request_does_not_retry_on_400`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val sleeps = mutableListOf<Long>()
            val store = InMemoryTokenStore()
            store.save(USER, validToken())
            server.enqueue(MockResponse.Builder().code(400).body("bad request").build())
            assertFailsWith<ToyotaApiException> {
                ToyotaController(
                    USER, PASS, OkHttpClient(), urls = urlsFor(server),
                    tokenStore = store, sleeper = { sleeps += it },
                ).request("GET", "/v3/telemetry")
            }
            assertEquals(emptyList(), sleeps)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `exception_message_redacts_password_and_token`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val store = InMemoryTokenStore()
            store.save(USER, validToken())
            server.enqueue(
                MockResponse.Builder().code(400)
                    .body("""{"password":"SEKR3T-PW","access_token":"SEKR3T-TOKEN"}""").build(),
            )
            val ex = assertFailsWith<ToyotaApiException> {
                ToyotaController(USER, PASS, OkHttpClient(), urls = urlsFor(server), tokenStore = store)
                    .request("GET", "/v3/telemetry")
            }
            val msg = ex.message!!
            assertTrue(!msg.contains("SEKR3T-PW") && !msg.contains("SEKR3T-TOKEN")) { "message: $msg" }
            assertTrue(msg.contains("REDACTED"))
        }
    }

    @Test
    fun `backoff_sequence_is_two_four_eight`() = runBlocking {
        MockWebServer().apply { start() }.use { server ->
            val sleeps = mutableListOf<Long>()
            val store = InMemoryTokenStore()
            store.save(USER, validToken())
            repeat(4) { server.enqueue(MockResponse.Builder().code(500).body("boom").build()) }
            assertFailsWith<ToyotaApiException> {
                ToyotaController(
                    USER, PASS, OkHttpClient(), urls = urlsFor(server),
                    tokenStore = store, sleeper = { sleeps += it },
                ).request("GET", "/v3/telemetry")
            }
            assertEquals(listOf(2L, 4L, 8L), sleeps)
        }
    }

    private fun urlsFor(server: MockWebServer) = ToyotaUrls(
        apiBase = server.url("/").toString().trimEnd('/'),
        authenticate = server.url("/authenticate").toString(),
        authorize = server.url("/authorize").toString(),
        accessToken = server.url("/token").toString(),
    )
}
