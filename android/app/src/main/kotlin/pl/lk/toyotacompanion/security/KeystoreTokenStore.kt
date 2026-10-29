package pl.lk.toyotacompanion.security

import java.security.MessageDigest
import java.time.Instant
import org.json.JSONObject
import pl.lk.toyotacompanion.core.toyota.TokenInfo
import pl.lk.toyotacompanion.core.toyota.TokenStore

/**
 * Toyota tokens encrypted at rest. The preference key is a SHA-256 of the
 * username, so the account name does not appear in the preferences file.
 * Serialized with `org.json` (part of Android): app has no kotlinx-serialization.
 */
class KeystoreTokenStore(private val store: EncryptedSecretStore) : TokenStore {
    override fun load(username: String): TokenInfo? {
        val key = keyFor(username)
        val text = store.getText(key) ?: return null
        return try {
            val json = JSONObject(text)
            TokenInfo(
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                uuid = json.getString("uuid"),
                expiration = Instant.parse(json.getString("expiration")),
            )
        } catch (e: Exception) {
            store.remove(key)
            null
        }
    }

    override fun save(username: String, token: TokenInfo) {
        val json = JSONObject()
            .put("accessToken", token.accessToken)
            .put("refreshToken", token.refreshToken)
            .put("uuid", token.uuid)
            .put("expiration", token.expiration.toString())
        store.putText(keyFor(username), json.toString())
    }

    override fun clear(username: String) = store.remove(keyFor(username))

    private fun keyFor(username: String): String =
        "token." + MessageDigest.getInstance("SHA-256").digest(username.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
