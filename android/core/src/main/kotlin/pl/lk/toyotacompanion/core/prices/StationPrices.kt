package pl.lk.toyotacompanion.core.prices

import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** How old the price on the page is, as the page says it ("dziś", "wczoraj", anything else). */
enum class PriceAge { TODAY, YESTERDAY, OLDER }

/** The 95 price of one station; `null` when nobody has entered one ("?" on the page). */
data class StationPrice(val stationId: String, val price95: BigDecimal?, val age: PriceAge?)

enum class PriceErrorKind { NETWORK, API, DATA }

/** A failed price request. SECURITY: only the kind and the HTTP status, never the page or the address. */
class FuelPriceException(val kind: PriceErrorKind, message: String) : RuntimeException(message)

/** Source of station prices; [TrojmiastoPriceClient] is the only implementation, tests use a fake. */
fun interface FuelPriceGateway {
    /** The listed stations among [stationIds]; one that the page does not list is simply absent. */
    suspend fun prices(stationIds: Set<String>): List<StationPrice>
}

/**
 * Prices of the Tri-City stations from the public page https://www.trojmiasto.pl/moto/paliwa/ (docs/SPEC.md §25).
 * One plain GET of the page: no parameter, no position, no account, no identifier - the stations are picked here, after
 * the download. The prices are entered by readers and stations, so they can be stale or wrong; the age the page shows is
 * carried along. The layout is parsed with regular expressions; when it changes the result is [PriceErrorKind.DATA],
 * never a wrong price.
 */
class TrojmiastoPriceClient(
    private val http: OkHttpClient,
    private val url: String = DEFAULT_URL,
    private val userAgent: String = USER_AGENT,
) : FuelPriceGateway {
    override suspend fun prices(stationIds: Set<String>): List<StationPrice> {
        val request = Request.Builder().url(url.toHttpUrl()).header("User-Agent", userAgent).get().build()
        val (status, body) = try {
            withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.code to (if (it.code in 200..299) readLimited(it.body) else "") } }
        } catch (e: IOException) {
            throw FuelPriceException(PriceErrorKind.NETWORK, "Price request failed: ${e.javaClass.simpleName}")
        }
        if (status !in 200..299) throw FuelPriceException(PriceErrorKind.API, "Price request failed (HTTP $status)")
        return parse(body, stationIds)
    }

    companion object {
        const val DEFAULT_URL = "https://www.trojmiasto.pl/moto/paliwa/"

        /** The page has some hundred kB; more than this is not the price list and is not read into memory (C22 D13). */
        const val MAX_PAGE_BYTES = 2L * 1024 * 1024

        private fun readLimited(body: okhttp3.ResponseBody): String {
            val source = body.source()
            if (source.request(MAX_PAGE_BYTES + 1)) {
                throw FuelPriceException(PriceErrorKind.DATA, "Price page larger than $MAX_PAGE_BYTES bytes")
            }
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            return source.buffer.readString(charset)
        }
        const val USER_AGENT = "toyota-companion"

        private val ROW = Regex("""<tr id="(\d+)" class="prices-table__item">(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
        private val CELL_95 = Regex("""<td title="95">(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
        private val PRICE = Regex("""prices-table__price">\s*(\d{1,2},\d{2})""")
        private val DATE = Regex("""prices-table__date">\s*([^<]+?)\s*<""")

        /** Pure, so the layout can be tested on a fixture. Fails with DATA when the page lists no station at all. */
        fun parse(page: String, stationIds: Set<String>): List<StationPrice> {
            val rows = ROW.findAll(page).toList()
            if (rows.isEmpty()) throw FuelPriceException(PriceErrorKind.DATA, "Price page has no station rows")
            return rows.filter { it.groupValues[1] in stationIds }.map { row ->
                val cell = CELL_95.find(row.groupValues[2])?.groupValues?.get(1).orEmpty()
                val price = PRICE.find(cell)?.groupValues?.get(1)?.replace(',', '.')?.let(::BigDecimal)
                    ?.takeIf { it.signum() > 0 }
                val age = if (price == null) null else when (DATE.find(cell)?.groupValues?.get(1)?.trim()) {
                    "dziś" -> PriceAge.TODAY
                    "wczoraj" -> PriceAge.YESTERDAY
                    else -> PriceAge.OLDER
                }
                StationPrice(row.groupValues[1], price, age)
            }
        }
    }
}

/** Builds the client; OkHttp stays inside `core`. No redirects: the page has none, and a redirect would be unexpected. */
object FuelPriceGatewayFactory {
    fun create(): FuelPriceGateway = TrojmiastoPriceClient(
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(java.time.Duration.ofSeconds(30))
            .build(),
    )
}
