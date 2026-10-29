package pl.lk.toyotacompanion.core.sync

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
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
}
