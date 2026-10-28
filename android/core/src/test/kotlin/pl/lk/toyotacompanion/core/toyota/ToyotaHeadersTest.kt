@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.toyota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UUID_MOCK = "11111111-2222-3333-4444-555555555555"

class ToyotaHeadersTest {

    @Test
    fun `data_request_headers_contain_guid_bearer_and_hmac_ref`() {
        val headers = ToyotaHeaders.dataHeaders(
            uuid = UUID_MOCK,
            accessToken = "TOKEN-ABC",
            vin = "JT-TEST-VIN",
            correlationId = "corr-1",
        )
        assertEquals(UUID_MOCK, headers["x-guid"])
        assertEquals(UUID_MOCK, headers["guid"])
        assertEquals("Bearer TOKEN-ABC", headers["authorization"])
        assertEquals(
            "202f85330d398dcdc7e4669743829e5e5af7119679bcd3cd9e981b5b89563a65",
            headers["x-client-ref"],
        ) { "HMAC vector from pytoyoda helpers.generate_hmac_sha256('2.14.0', uuid)" }
        assertEquals(ToyotaConst.X_API_KEY, headers["x-api-key"])
        assertEquals(ToyotaConst.X_API_KEY, headers["API_KEY"])
        assertEquals("ONEAPP", headers["x-channel"])
        assertEquals("T", headers["x-brand"])
        assertEquals("EU", headers["x-region"])
        assertEquals("EU", headers["x-user-region"])
        assertEquals(ToyotaConst.CLIENT_VERSION, headers["x-appversion"])
        assertEquals("corr-1", headers["x-correlationid"])
        assertEquals("okhttp/4.10.0", headers["user-agent"])
        assertEquals("JT-TEST-VIN", headers["vin"])
    }

    @Test
    fun `data_request_headers_omit_datetime_and_content_type`() {
        // pytoyoda attaches datetime/Content-Type only to the alias-PUT
        // (api.py:149); the ported GETs do not send them (controller.py:514-528).
        val headers = ToyotaHeaders.dataHeaders(UUID_MOCK, "TOKEN")
        assertNull(headers["datetime"])
        assertNull(headers["Content-Type"])
        assertNull(headers["vin"])
    }

    @Test
    fun `hmac_matches_python_vector`() {
        assertEquals(
            "202f85330d398dcdc7e4669743829e5e5af7119679bcd3cd9e981b5b89563a65",
            ToyotaHeaders.hmacSha256Hex(ToyotaConst.CLIENT_VERSION, UUID_MOCK),
        )
    }
}

class ToyotaJwtUuidTest {

    private val validJwt = generateJwt(UUID_MOCK, "oneappsdkclient")

    @Test
    fun `uuid extracted from id_token`() {
        assertEquals(UUID_MOCK, JwtUuid.extractUuid(validJwt))
    }

    @Test
    fun `token with another audience still returns uuid`() {
        // parity with pytoyoda: verify_signature=False disables aud/exp checks
        val jwt = generateJwt(UUID_MOCK, "someone-else")
        assertEquals(UUID_MOCK, JwtUuid.extractUuid(jwt))
    }

    @Test
    fun `malformed token raises login exception`() {
        assertFailsWith<ToyotaLoginException> { JwtUuid.extractUuid("not.a.jwt") }
        assertFailsWith<ToyotaLoginException> { JwtUuid.extractUuid("!!!") }
    }

    companion object {
        fun generateJwt(uuid: String, audience: String): String {
            fun enc(s: String) = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.toByteArray(Charsets.UTF_8))
            val header = enc("""{"alg":"RS256","typ":"JWT"}""")
            val payload = enc("""{"uuid":"$uuid","aud":"$audience","sub":"mock-user"}""")
            return "$header.$payload.ZmFrZQ"
        }
    }
}
