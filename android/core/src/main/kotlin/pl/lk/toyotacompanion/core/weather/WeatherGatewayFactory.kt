package pl.lk.toyotacompanion.core.weather

import java.time.Duration
import okhttp3.OkHttpClient

/**
 * Builds the weather client; OkHttp stays inside `core`, like the Toyota client (docs/ARCHITECTURE.md).
 * One request at a time is all the free service serves, so the client has no connection pool tricks - just timeouts.
 */
object WeatherGatewayFactory {
    fun create(): WeatherGateway = OpenMeteoClient(
        OkHttpClient.Builder()
            // The query carries the rounded position; a redirect would repeat it on another host.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(45))
            .build(),
    )
}
