package pl.lk.toyotacompanion.core.toyota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/SECURITY.md: Comprehensive unit tests and edge cases for [ToyotaSecrets.sanitize] and [ToyotaSecrets.maskVin].
 * Verifies masking of tokens, Bearer headers, authorization codes, VIN numbers, and bounds/truncation behavior.
 */
class ToyotaSecretsSanitizeTest {

    // ---------------- token= masking ----------------

    @Test
    fun `token and access_token variants are redacted with various delimiters and casings`() {
        // Query parameters
        assertEquals("token=REDACTED", ToyotaSecrets.sanitize("token=secret_value_123"))
        assertEquals("access_token=REDACTED", ToyotaSecrets.sanitize("access_token=ya29.a0AfH6SM..."))
        assertEquals("refresh_token=REDACTED", ToyotaSecrets.sanitize("refresh_token=1//0gXYZ..."))
        assertEquals("token_id=REDACTED", ToyotaSecrets.sanitize("token_id=tok-999"))
        assertEquals("tokenid=REDACTED", ToyotaSecrets.sanitize("tokenid=tok-888"))
        assertEquals("x_access_token=REDACTED", ToyotaSecrets.sanitize("x_access_token=custom-tok"))

        // Spaces around delimiter
        assertEquals("token : REDACTED", ToyotaSecrets.sanitize("token : secret123"))
        assertEquals("access_token = REDACTED", ToyotaSecrets.sanitize("access_token = secret123"))

        // Multiple tokens in query string
        assertEquals(
            "token=REDACTED&refresh_token=REDACTED&scope=read",
            ToyotaSecrets.sanitize("token=abc12345&refresh_token=def67890&scope=read"),
        )
    }

    @Test
    fun `token fields in JSON payload are redacted preserving JSON structure`() {
        assertEquals("""{"token":"REDACTED"}""", ToyotaSecrets.sanitize("""{"token":"secret_123"}"""))
        assertEquals(
            """{"access_token":"REDACTED","expires_in":3600}""",
            ToyotaSecrets.sanitize("""{"access_token":"secret_abc","expires_in":3600}"""),
        )
        assertEquals(
            """{"refreshToken":"REDACTED","scope":"all"}""",
            ToyotaSecrets.sanitize("""{"refreshToken":"xyz","scope":"all"}"""),
        )
        assertEquals("""{"id_token":"REDACTED"}""", ToyotaSecrets.sanitize("""{"id_token":"eyJhbG..."}"""))
        assertEquals("""{'token':'REDACTED'}""", ToyotaSecrets.sanitize("""{'token':'single_quote'}"""))
    }

    // ---------------- Bearer masking ----------------

    @Test
    fun `bearer tokens of at least 8 characters are redacted in headers and sentences`() {
        // Standard Bearer header
        assertEquals(
            "Authorization: REDACTED",
            ToyotaSecrets.sanitize("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc"),
        )

        // Case insensitivity
        assertEquals("bearer REDACTED", ToyotaSecrets.sanitize("bearer 12345678"))
        assertEquals("Bearer REDACTED", ToyotaSecrets.sanitize("Bearer ABCDEF123456"))
        assertEquals("BEARER REDACTED", ToyotaSecrets.sanitize("BEARER XYZ987654321"))

        // Tokens with base64 and URL-safe characters
        assertEquals("Bearer REDACTED", ToyotaSecrets.sanitize("Bearer aA0._~+/=-12345678"))

        // Inside an error response body
        assertEquals(
            """{"error":"invalid_token","detail":"Bearer REDACTED expired"}""",
            ToyotaSecrets.sanitize("""{"error":"invalid_token","detail":"Bearer eyJhbGciOiJ.123 expired"}"""),
        )
    }

    @Test
    fun `bearer word next to short word or without credential is not damaged`() {
        // Less than 8 characters - not treated as a sensitive token credential
        assertEquals("Bearer 1234567", ToyotaSecrets.sanitize("Bearer 1234567"))
        assertEquals("bearer short", ToyotaSecrets.sanitize("bearer short"))
        assertEquals("The Bearer token mechanism", ToyotaSecrets.sanitize("The Bearer token mechanism"))
        assertEquals("Bearer", ToyotaSecrets.sanitize("Bearer"))
    }

    // ---------------- code= masking ----------------

    @Test
    fun `authorization codes and code parameters are redacted`() {
        assertEquals("code=REDACTED", ToyotaSecrets.sanitize("code=AUTH_CODE_xyz"))
        assertEquals("auth_code=REDACTED", ToyotaSecrets.sanitize("auth_code=123456"))
        assertEquals("authorization_code=REDACTED", ToyotaSecrets.sanitize("authorization_code=secret-code"))
        assertEquals(
            "https://toyota.com/oauth/callback?code=REDACTED&state=xyz123",
            ToyotaSecrets.sanitize("https://toyota.com/oauth/callback?code=AUTHCODE_98765&state=xyz123"),
        )
        assertEquals(
            """{"code":"REDACTED","status":"ok"}""",
            ToyotaSecrets.sanitize("""{"code":"secret_code","status":"ok"}"""),
        )
        assertEquals("errorcode=REDACTED", ToyotaSecrets.sanitize("errorcode=503"))
    }

    @Test
    fun `plain word code in normal sentences is preserved`() {
        assertEquals("Response code 200 received", ToyotaSecrets.sanitize("Response code 200 received"))
        assertEquals("Postal code 80-001", ToyotaSecrets.sanitize("Postal code 80-001"))
        assertEquals("Check status code and retry", ToyotaSecrets.sanitize("Check status code and retry"))
    }

    // ---------------- VIN masking ----------------

    @Test
    fun `vin in key value pairs is redacted by sanitize`() {
        assertEquals("vin=REDACTED", ToyotaSecrets.sanitize("vin=SB1EG76J10E012345"))
        assertEquals("VIN: REDACTED", ToyotaSecrets.sanitize("VIN: SB1EG76J10E012345"))
        assertEquals("""{"vin":"REDACTED"}""", ToyotaSecrets.sanitize("""{"vin":"SB1EG76J10E012345"}"""))
        assertEquals("""{"vehicle_vin":"REDACTED"}""", ToyotaSecrets.sanitize("""{"vehicle_vin":"SB1EG76J10E012345"}"""))
        assertEquals("car_vin=REDACTED&speed=60", ToyotaSecrets.sanitize("car_vin=SB1EG76J10E012345&speed=60"))
    }

    @Test
    fun `maskVin replaces bare VIN keeping only last 4 characters`() {
        val vin = "SB1EG76J10E012345"
        val masked = "*************2345" // 13 asterisks + 4 characters = 17 characters

        assertEquals("Vehicle $masked updated", ToyotaSecrets.maskVin("Vehicle $vin updated", vin))
        assertEquals(
            "Pair: $masked and $masked",
            ToyotaSecrets.maskVin("Pair: $vin and $vin", vin),
        )
    }

    @Test
    fun `maskVin masks short VINs completely`() {
        assertEquals("****", ToyotaSecrets.maskVin("VIN1", "VIN1"))
        assertEquals("****", ToyotaSecrets.maskVin("1234", "1234"))
        assertEquals("*", ToyotaSecrets.maskVin("a", "a"))
        assertEquals("**", ToyotaSecrets.maskVin("ab", "ab"))
        // 5 characters: keeps last 4, masks first 1
        assertEquals("*2345", ToyotaSecrets.maskVin("12345", "12345"))
    }

    @Test
    fun `maskVin handles null blank and missing VIN gracefully`() {
        assertEquals("Hello world", ToyotaSecrets.maskVin("Hello world", null))
        assertEquals("Hello world", ToyotaSecrets.maskVin("Hello world", ""))
        assertEquals("Hello world", ToyotaSecrets.maskVin("Hello world", "   "))
        assertEquals("Hello world", ToyotaSecrets.maskVin("Hello world", "SB1EG76J10E012345"))
    }

    @Test
    fun `sanitize and maskVin work together on complex diagnostic messages`() {
        val vin = "SB1EG76J10E012345"
        val rawMessage = "Failed sync for vin=$vin: token=secret123, raw vehicle $vin not responding"

        val sanitized = ToyotaSecrets.sanitize(rawMessage)
        assertEquals("Failed sync for vin=REDACTED token=REDACTED, raw vehicle $vin not responding", sanitized)

        val fullyMasked = ToyotaSecrets.maskVin(sanitized, vin)
        assertEquals(
            "Failed sync for vin=REDACTED token=REDACTED, raw vehicle *************2345 not responding",
            fullyMasked,
        )
    }

    // ---------------- Edge cases: whitespace, length capping, newlines ----------------

    @Test
    fun `empty and blank texts are handled without errors`() {
        assertEquals("", ToyotaSecrets.sanitize(""))
        assertEquals("   ", ToyotaSecrets.sanitize("   "))
    }

    @Test
    fun `newlines and carriage returns are flattened into single spaces`() {
        assertEquals("line 1 line 2 line 3", ToyotaSecrets.sanitize("line 1\nline 2\rline 3"))
        assertEquals("a  b", ToyotaSecrets.sanitize("a\r\nb"))
        assertEquals("Error: token=REDACTED in line 2", ToyotaSecrets.sanitize("Error:\ntoken=secret123\nin line 2"))
    }

    @Test
    fun `text exceeding MAX_LEN of 200 characters is capped with an ellipsis`() {
        val exact200 = "x".repeat(200)
        assertEquals(exact200, ToyotaSecrets.sanitize(exact200))

        val exact201 = "x".repeat(201)
        val sanitized201 = ToyotaSecrets.sanitize(exact201)
        assertEquals(201, sanitized201.length) // 200 characters + '…'
        assertTrue(sanitized201.endsWith("…"))
        assertEquals("x".repeat(200) + "…", sanitized201)

        val longText = "Error message with token=super_secret_token_1234567890 and lots of padding: " + "a".repeat(300)
        val result = ToyotaSecrets.sanitize(longText)
        assertTrue(result.contains("token=REDACTED"))
        assertTrue(result.endsWith("…"))
        assertEquals(201, result.length)
    }

    @Test
    fun `password and secret keys are redacted regardless of case and prefix`() {
        assertEquals("password=REDACTED", ToyotaSecrets.sanitize("password=secretPassword123"))
        assertEquals("user_password: REDACTED", ToyotaSecrets.sanitize("user_password: mySecret"))
        assertEquals("client_secret=REDACTED", ToyotaSecrets.sanitize("client_secret=topsecret!"))
        assertEquals("""{"clientSecret":"REDACTED"}""", ToyotaSecrets.sanitize("""{"clientSecret":"val"}"""))
        assertEquals("""{"newPassword":"REDACTED"}""", ToyotaSecrets.sanitize("""{"newPassword":"val"}"""))
    }
}
