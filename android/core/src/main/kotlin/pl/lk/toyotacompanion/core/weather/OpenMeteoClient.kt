package pl.lk.toyotacompanion.core.weather

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** One day of weather at one place. A value the service had no data for is `null`, never 0. */
data class WeatherDay(
    val date: LocalDate,
    val tempMeanC: Double?,
    val tempMinC: Double?,
    val tempMaxC: Double?,
    val precipitationMm: Double?,
    val precipitationHours: Double?,
    val windMaxKmh: Double?,
)

/** Source of daily weather; [OpenMeteoClient] is the only implementation, tests use a fake. */
fun interface WeatherGateway {
    suspend fun daily(latitude: Double, longitude: Double, start: LocalDate, end: LocalDate): List<WeatherDay>
}

/** Why a weather request failed; the screen shows a fixed text per kind, never the server's words. */
enum class WeatherErrorKind { NETWORK, RATE_LIMIT, API, DATA }

/**
 * A failed weather request. SECURITY: the message holds only the kind and the HTTP status - never the
 * response body, the URL or a coordinate (docs/SECURITY.md).
 */
class WeatherFetchException(
    val kind: WeatherErrorKind,
    message: String,
    /** `Retry-After` of a 429, when the server sent one. */
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Daily historical weather from Open-Meteo (PRD F15, docs/SPEC.md §23): `GET /v1/archive`, no key, one point
 * per request. Only the position rounded to [COORDINATE_SCALE] decimals (about 1 km) leaves the app, plus the
 * dates and the variable list - no VIN, no account, no token, no id of a trip. The coordinates are rounded
 * here as well, so no caller can send a finer one.
 *
 * @param today clock of the request: `end` is cut to the day before, because the archive answers 400 for
 * a future date and has no data for today yet
 */
class OpenMeteoClient(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = USER_AGENT,
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
) : WeatherGateway {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Days from [start] to [end] (inclusive, both local dates of the point) in the order of the service.
     * An empty list when the range is empty after cutting [end] to yesterday.
     */
    override suspend fun daily(latitude: Double, longitude: Double, start: LocalDate, end: LocalDate): List<WeatherDay> {
        if (!latitude.isFinite() || !longitude.isFinite()) {
            throw WeatherFetchException(WeatherErrorKind.DATA, "Weather position is not finite")
        }
        val last = minOf(end, today().minusDays(1))
        if (start.isAfter(last)) return emptyList()
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("v1/archive")
            .addQueryParameter("latitude", rounded(latitude))
            .addQueryParameter("longitude", rounded(longitude))
            .addQueryParameter("start_date", start.toString())
            .addQueryParameter("end_date", last.toString())
            .addQueryParameter("daily", DAILY_VARIABLES)
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("timeformat", "iso8601")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
        val (status, retryAfter, body) = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { Triple(it.code, it.header("Retry-After"), it.body?.string().orEmpty()) }
            }
        } catch (e: IOException) {
            // The IOException text may name the host and the query; only its class is kept.
            throw WeatherFetchException(WeatherErrorKind.NETWORK, "Weather request failed: ${e.javaClass.simpleName}")
        }
        when {
            status == 429 -> throw WeatherFetchException(
                WeatherErrorKind.RATE_LIMIT,
                "Weather service refused the request (HTTP 429)",
                retryAfterSeconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
            )
            status !in 200..299 -> throw WeatherFetchException(WeatherErrorKind.API, "Weather request failed (HTTP $status)")
        }
        return parse(body)
    }

    private fun parse(body: String): List<WeatherDay> {
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw WeatherFetchException(WeatherErrorKind.DATA, "Weather response is not JSON")
        }
        // A JSON array answers a request for several points; this client sends one.
        val daily = (root as? JsonObject)?.get("daily") as? JsonObject
            ?: throw WeatherFetchException(WeatherErrorKind.DATA, "Weather response has no daily block")
        val times = (daily["time"] as? JsonArray)
            ?: throw WeatherFetchException(WeatherErrorKind.DATA, "Weather response has no dates")
        fun column(name: String): JsonArray? = daily[name] as? JsonArray
        val mean = column("temperature_2m_mean")
        val min = column("temperature_2m_min")
        val max = column("temperature_2m_max")
        val rain = column("precipitation_sum")
        val hours = column("precipitation_hours")
        val wind = column("wind_speed_10m_max")
        return times.mapIndexed { i, element ->
            val date = try {
                LocalDate.parse((element as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty())
            } catch (e: Exception) {
                throw WeatherFetchException(WeatherErrorKind.DATA, "Weather response has a bad date")
            }
            WeatherDay(date, value(mean, i), value(min, i), value(max, i), value(rain, i), value(hours, i), value(wind, i))
        }
    }

    /** A number, or `null` for JSON null, a missing column or anything that is not a finite number. */
    private fun value(column: JsonArray?, index: Int): Double? {
        val element: JsonElement = column?.getOrNull(index) ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://archive-api.open-meteo.com"
        const val USER_AGENT = "toyota-companion"
        const val COORDINATE_SCALE = 2
        const val DAILY_VARIABLES =
            "temperature_2m_mean,temperature_2m_min,temperature_2m_max,precipitation_sum,precipitation_hours,wind_speed_10m_max"

        /** `54.37`: HALF_UP to [COORDINATE_SCALE] decimals, written with a dot. */
        fun rounded(value: Double): String =
            BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP).toPlainString()
    }
}
