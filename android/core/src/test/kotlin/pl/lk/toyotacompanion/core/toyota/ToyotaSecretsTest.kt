package pl.lk.toyotacompanion.core.toyota

import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/SECURITY.md: what [ToyotaSecrets.sanitize] hides in an error text or a report, and what it must leave alone. */
class ToyotaSecretsTest {

    @Test
    fun `a key value pair of a secret is redacted, the key stays`() {
        assertEquals("token=REDACTED", ToyotaSecrets.sanitize("token=abc123"))
        assertEquals("token: REDACTED", ToyotaSecrets.sanitize("token: abc123"))
        assertEquals("access_token=REDACTED", ToyotaSecrets.sanitize("access_token=SECRET123"))
        assertEquals("refresh_token=REDACTED&x=1", ToyotaSecrets.sanitize("refresh_token=xyz&x=1"))
        assertEquals("code=REDACTED&state=s", ToyotaSecrets.sanitize("code=AUTHCODE123&state=s"))
        assertEquals("x_access_token=REDACTED", ToyotaSecrets.sanitize("x_access_token=abc"))
    }

    @Test
    fun `json keys are redacted in snake case and camel case`() {
        assertEquals("""{"access_token":"REDACTED","x":1}""", ToyotaSecrets.sanitize("""{"access_token":"abc","x":1}"""))
        assertEquals("""{"accessToken":"REDACTED","x":1}""", ToyotaSecrets.sanitize("""{"accessToken":"abc","x":1}"""))
        assertEquals("""{"refreshToken":"REDACTED"}""", ToyotaSecrets.sanitize("""{"refreshToken":"abc"}"""))
        assertEquals("""{"idToken":"REDACTED"}""", ToyotaSecrets.sanitize("""{"idToken":"abc"}"""))
    }

    @Test
    fun `an authorization header hides the scheme and the credential`() {
        assertEquals("Authorization: REDACTED", ToyotaSecrets.sanitize("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc"))
        assertEquals("Authorization: REDACTED", ToyotaSecrets.sanitize("Authorization: Basic dXNlcjpwYXNz"))
        assertEquals("Bearer REDACTED", ToyotaSecrets.sanitize("Bearer eyJhbGciOiJIUzI1NiJ9.abc"))
    }

    @Test
    fun `the plain word token in a sentence is not destroyed`() {
        assertEquals("Token retrieval failed.", ToyotaSecrets.sanitize("Token retrieval failed."))
        assertEquals("Podaj token w aplikacji", ToyotaSecrets.sanitize("Podaj token w aplikacji"))
        assertEquals("Bearer token", ToyotaSecrets.sanitize("Bearer token"))
        assertEquals("Token issued for 2 hours", ToyotaSecrets.sanitize("Token issued for 2 hours"))
    }

    @Test
    fun `a longer name that ends like a secret key is redacted too`() {
        // Hiding too much is harmless; a sanitizer that lets `sessiontoken=` through is not.
        assertEquals("mytoken=REDACTED", ToyotaSecrets.sanitize("mytoken=abc123"))
        assertEquals("sessionToken=REDACTED", ToyotaSecrets.sanitize("sessionToken=abc123"))
        assertEquals("newPassword=REDACTED", ToyotaSecrets.sanitize("newPassword=hunter2"))
        assertEquals("client_secret=REDACTED&x=1", ToyotaSecrets.sanitize("client_secret=s3cr3t&x=1"))
        assertEquals("errorcode=REDACTED", ToyotaSecrets.sanitize("errorcode=503"))
        assertEquals("https://host/cb?code=REDACTED&state=s", ToyotaSecrets.sanitize("https://host/cb?code=AUTH123&state=s"))
    }

    @Test
    fun `other status values stay readable`() {
        assertEquals("status=503, body=ok", ToyotaSecrets.sanitize("status=503, body=ok"))
    }
}
