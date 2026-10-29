package pl.lk.toyotacompanion.core.sync

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import pl.lk.toyotacompanion.core.toyota.TokenInfo
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException
import pl.lk.toyotacompanion.core.toyota.ToyotaDataException
import pl.lk.toyotacompanion.core.toyota.ToyotaInvalidUsernameException
import pl.lk.toyotacompanion.core.toyota.ToyotaLoginException
import pl.lk.toyotacompanion.core.toyota.ToyotaNetworkException

/** docs/SPEC.md §9: which Toyota failure means which login message. */
class LoginCheckTest {
    private val token = TokenInfo(
        accessToken = "secret-access-token",
        refreshToken = "secret-refresh-token",
        uuid = "uuid-1",
        expiration = Instant.parse("2099-01-01T00:00:00Z"),
    )

    @Test
    fun `wrong password is invalid credentials`() {
        assertEquals(
            LoginFailure.INVALID_CREDENTIALS,
            classifyLoginFailure(ToyotaLoginException("Authentication Failed. status=401.")),
        )
    }

    @Test
    fun `unknown account is invalid credentials`() {
        assertEquals(
            LoginFailure.INVALID_CREDENTIALS,
            classifyLoginFailure(ToyotaInvalidUsernameException("Authentication Failed. User Not Found.")),
        )
    }

    @Test
    fun `transport failure is network`() {
        assertEquals(
            LoginFailure.NETWORK,
            classifyLoginFailure(ToyotaNetworkException("Network error calling Toyota API")),
        )
    }

    @Test
    fun `api error is service`() {
        assertEquals(LoginFailure.SERVICE, classifyLoginFailure(ToyotaApiException(503, "Unavailable")))
    }

    @Test
    fun `unexpected payload is service`() {
        assertEquals(LoginFailure.SERVICE, classifyLoginFailure(ToyotaDataException("Expected JSON object")))
    }

    @Test
    fun `unknown error is service`() {
        assertEquals(LoginFailure.SERVICE, classifyLoginFailure(IllegalStateException("bug")))
    }

    @Test
    fun `cancellation is rethrown not classified`() {
        assertFailsWith<CancellationException> { classifyLoginFailure(CancellationException("cancelled")) }
    }

    @Test
    fun `successful check does not print the token`() {
        val text = LoginCheck.Ok(token).toString() + LoginCheck.Failed(LoginFailure.NETWORK).toString()
        assertFalse("secret-access-token" in text, text)
        assertFalse("secret-refresh-token" in text, text)
    }
}
