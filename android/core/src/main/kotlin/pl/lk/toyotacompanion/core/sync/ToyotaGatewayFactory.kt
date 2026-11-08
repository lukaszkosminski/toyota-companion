package pl.lk.toyotacompanion.core.sync

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import pl.lk.toyotacompanion.core.toyota.InMemoryTokenStore
import pl.lk.toyotacompanion.core.toyota.TokenStore
import pl.lk.toyotacompanion.core.toyota.ToyotaApi
import pl.lk.toyotacompanion.core.toyota.ToyotaController
import pl.lk.toyotacompanion.core.vehicle.ToyotaVehicleDataGateway
import pl.lk.toyotacompanion.core.vehicle.VehicleDataGateway

/**
 * Builds the real [ToyotaGateway] without exposing OkHttp to the app module
 * (`core` keeps OkHttp as an implementation dependency). One [OkHttpClient]
 * is shared by every gateway: it owns the connection pool and threads.
 */
object ToyotaGatewayFactory {
    private val client: OkHttpClient by lazy { OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build() } // pytoyoda controller.py:73, timeout=60

    /**
     * The controller of one account on the shared client. Keep one per account and pass it to [create] and
     * [createRoutes]: its lock makes parallel callers renew the token once (single flight).
     */
    fun controller(username: String, password: String, tokenStore: TokenStore): ToyotaController =
        ToyotaController(username, password, client, tokenStore = tokenStore)

    fun create(controller: ToyotaController): ToyotaGateway = ToyotaApiGateway(ToyotaApi(controller))

    /** A fresh controller per gateway: no shared token lock. Prefer [controller] + [create] for a long-lived app. */
    fun create(username: String, password: String, tokenStore: TokenStore): ToyotaGateway =
        create(controller(username, password, tokenStore))

    /** The route of a single trip (docs/SPEC.md §27); the same client, account and token as [create]. */
    fun createRoutes(controller: ToyotaController): pl.lk.toyotacompanion.core.routes.TripRouteGateway =
        pl.lk.toyotacompanion.core.routes.ToyotaTripRouteGateway(ToyotaApi(controller))

    fun createRoutes(username: String, password: String, tokenStore: TokenStore): pl.lk.toyotacompanion.core.routes.TripRouteGateway =
        createRoutes(controller(username, password, tokenStore))

    /**
     * Vehicle status, last known position and vehicle health (docs/SPEC.md §42-§44): read-only cloud
     * cache on the same controller as [create], so the token is renewed once for every reader.
     */
    fun vehicleData(controller: ToyotaController): VehicleDataGateway =
        ToyotaVehicleDataGateway(ToyotaApi(controller))

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
