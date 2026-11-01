package pl.lk.toyotacompanion.core.prices

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

private fun row(id: String, p95: String?, age95: String, extra: String = "") = """
<tr id="$id" class="prices-table__item">
  <td><a href="javascript:void(0);" id="star_$id"></a></td>
  <td><h2 id="name-$id" class="prices-table__name"><a href="x">Stacja $id</a></h2></td>
${if (p95 == null) "<td>&nbsp;</td>" else """<td title="95">
            <span class="prices-table__price">
            $p95
                            <span class="prices-table__date">$age95</span>
                    </span>
            </td>"""}
<td title="98"><span class="prices-table__price">7,15<span class="prices-table__date">dziś</span></span></td>
$extra
</tr>"""

private val PAGE = "<html><table>" + row("33831", "6,30", "dziś") + row("97185", "6,29", "wczoraj") +
    row("1", "6,99", "przedwczoraj") + row("2", null, "") + "</table></html>"

class TrojmiastoPriceClientTest {
    private val wanted = setOf("33831", "97185", "2", "404")

    @Test
    fun `the 95 price and its age are read for the wanted stations only`() {
        val prices = TrojmiastoPriceClient.parse(PAGE, wanted)
        assertEquals(
            listOf(
                StationPrice("33831", BigDecimal("6.30"), PriceAge.TODAY),
                StationPrice("97185", BigDecimal("6.29"), PriceAge.YESTERDAY),
                StationPrice("2", null, null),
            ),
            prices,
        )
    }

    @Test
    fun `an older date is OLDER and a station the page does not list is absent`() {
        assertEquals(PriceAge.OLDER, TrojmiastoPriceClient.parse(PAGE, setOf("1")).single().age)
        assertEquals(emptyList(), TrojmiastoPriceClient.parse(PAGE, setOf("404")))
    }

    @Test
    fun `a page without station rows is a data failure, never a price`() {
        val e = assertFailsWith<FuelPriceException> { TrojmiastoPriceClient.parse("<html>changed</html>", wanted) }
        assertEquals(PriceErrorKind.DATA, e.kind)
    }

    @Test
    fun `a zero or garbage price is no price`() {
        val page = "<table>" + row("1", "0,00", "dziś") + "</table>"
        assertNull(TrojmiastoPriceClient.parse(page, setOf("1")).single().price95)
    }

    @Test
    fun `the request is one plain GET with no parameter and no secret`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body(PAGE).build())
            val client = TrojmiastoPriceClient(OkHttpClient(), url = server.url("/moto/paliwa/").toString())
            val prices = runBlocking { client.prices(wanted) }
            assertEquals(3, prices.size)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/moto/paliwa/", request.url.encodedPath)
            assertEquals(emptySet(), request.url.queryParameterNames)
            assertEquals("toyota-companion", request.headers["User-Agent"])
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Cookie"])
        }
    }

    @Test
    fun `http errors and a missing connection are failures without the page text`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).body("secret page").build())
            val client = TrojmiastoPriceClient(OkHttpClient(), url = server.url("/").toString())
            val e = assertFailsWith<FuelPriceException> { runBlocking { client.prices(wanted) } }
            assertEquals(PriceErrorKind.API, e.kind)
            assertFalse(e.message.orEmpty().contains("secret"))
        }
        val down = TrojmiastoPriceClient(OkHttpClient(), url = "http://127.0.0.1:1/")
        val e = assertFailsWith<FuelPriceException> { runBlocking { down.prices(wanted) } }
        assertEquals(PriceErrorKind.NETWORK, e.kind)
        assertFalse(e.message.orEmpty().contains("127.0.0.1"))
    }
}
