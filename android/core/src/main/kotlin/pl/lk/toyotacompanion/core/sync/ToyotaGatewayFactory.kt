package pl.lk.toyotacompanion.core.sync

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import pl.lk.toyotacompanion.core.toyota.InMemoryTokenStore
import pl.lk.toyotacompanion.core.toyota.TokenStore
import pl.lk.toyotacompanion.core.toyota.ToyotaApi
import pl.lk.toyotacompanion.core.toyota.ToyotaController

/**
 * Builds the real [ToyotaGateway] without exposing OkHttp to the app module
 * (`core` keeps OkHttp as an implementation dependency). One [OkHttpClient]
 * is shared by every gateway: it owns the connection pool and threads.
 */
object ToyotaGatewayFactory {
    private val client: OkHttpClient by lazy { OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build() } // pytoyoda controller.py:73, timeout=60

    fun create(username: String, password: String, tokenStore: TokenStore): ToyotaGateway =
        ToyotaApiGateway(ToyotaApi(ToyotaController(username, password, client, tokenStore = tokenStore)))

    /**
     * Checks typed credentials against the real account before anything is
     * stored (docs/SPEC.md §9). Authentication only: no `refresh_status`, no
     * data endpoints.
     *
     * The token store is always a fresh in-memory one: reusing the stored token
     * would let `login()` succeed on a cached token and approve a wrong password.
     * On failure nothing is written anywhere; on success the caller decides
     * where to persist [LoginCheck.Ok.token].
     */
    suspend fun verifyLogin(username: String, password: String): LoginCheck {
        val store = InMemoryTokenStore()
        val controller = ToyotaController(username, password, client, tokenStore = store)
        return try {
            controller.login()
            val token = store.load(username)
                ?: throw IllegalStateException("Toyota login reported success without a token")
            LoginCheck.Ok(token)
        } catch (e: Exception) {
            LoginCheck.Failed(classifyLoginFailure(e))
        }
    }
}
