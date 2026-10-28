package pl.lk.toyotacompanion.core.toyota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Extracts the account `uuid` claim from the OAuth `id_token` JWT.
 *
 * Mirrors pytoyoda `controller.py:371-376`: signature is NOT verified
 * PyJWT with `options={"verify_signature": False}` also disables
 * verify_aud/verify_exp and ignores the `audience` argument, so the
 * audience is intentionally NOT checked here (parity with pytoyoda).
 * The uuid is used only as an API identifier header.
 */
object JwtUuid {
    private val json = Json { ignoreUnknownKeys = true }

    fun extractUuid(idToken: String): String {
        val parts = idToken.split('.')
        if (parts.size < 2) {
            throw ToyotaLoginException("id_token is not a JWT")
        }
        val payload = try {
            val bytes = java.util.Base64.getUrlDecoder().decode(padBase64(parts[1]))
            String(bytes, Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw ToyotaLoginException("id_token payload is not valid base64url", e)
        }
        val obj = try {
            json.parseToJsonElement(payload) as? JsonObject
                ?: throw ToyotaLoginException("id_token payload is not a JSON object")
        } catch (e: ToyotaLoginException) {
            throw e
        } catch (e: Exception) {
            throw ToyotaLoginException("id_token payload is not valid JSON", e)
        }
        val uuid = (obj["uuid"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (uuid.isNullOrEmpty()) {
            throw ToyotaLoginException("id_token has no uuid claim")
        }
        return uuid
    }

    private fun padBase64(value: String): String =
        value + "=".repeat((4 - value.length % 4) % 4)
}
