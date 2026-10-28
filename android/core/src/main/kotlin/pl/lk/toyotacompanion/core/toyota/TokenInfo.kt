package pl.lk.toyotacompanion.core.toyota

import java.time.Instant

/**
 * OAuth token set for one Toyota account.
 * Port of pytoyoda `controller.py:54-61` (TokenInfo dataclass).
 */
data class TokenInfo(
    val accessToken: String,
    val refreshToken: String,
    val uuid: String,
    val expiration: Instant,
) {
    fun isValidAt(now: Instant): Boolean = expiration.isAfter(now)
}

/**
 * Storage abstraction replacing pytoyoda's in-process username cache
 * (`controller.py:66`). The Android app will provide an encrypted
 * (Keystore-backed) implementation; core ships in-memory only.
 */
interface TokenStore {
    fun load(username: String): TokenInfo?

    fun save(username: String, token: TokenInfo)

    fun clear(username: String)
}

class InMemoryTokenStore : TokenStore {
    private val tokens = mutableMapOf<String, TokenInfo>()

    override fun load(username: String): TokenInfo? = tokens[username]

    override fun save(username: String, token: TokenInfo) {
        tokens[username] = token
    }

    override fun clear(username: String) {
        tokens.remove(username)
    }
}
