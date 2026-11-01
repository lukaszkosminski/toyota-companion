package pl.lk.toyotacompanion.ui

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private fun record(sLat: Double?, sLon: Double?, eLat: Double?, eLon: Double?) = TripRecord(
    trip = Trip("t", Instant.parse("2026-10-30T08:00:00Z"), Instant.parse("2026-10-30T09:00:00Z"), 10.0, BigDecimal("0.5"), null),
    vin = "VINAAA111",
    startLat = sLat,
    startLon = sLon,
    endLat = eLat,
    endLon = eLon,
)

/** docs/SPEC.md §10.3, §18: what may leave the app toward a map, and what the trip window offers. */
@RunWith(RobolectricTestRunner::class)
class MapLinkTest {
    @Test
    fun `a trip with both ends gets start then end, rounded to three decimals`() {
        val points = tripMapPoints(record(54.37249, 18.63849, 54.40051, 18.60049))
        assertEquals(listOf(Texts.MAP_START, Texts.MAP_END), points.map { it.label })
        assertEquals(listOf(54.372 to 18.638, 54.401 to 18.6), points.map { it.lat to it.lon })
    }

    @Test
    fun `only the ends with a position are on the map and none means no map`() {
        assertEquals(listOf(Texts.MAP_START), tripMapPoints(record(54.3, 18.6, null, null)).map { it.label })
        assertEquals(listOf(Texts.MAP_END), tripMapPoints(record(null, null, 54.3, 18.6)).map { it.label })
        assertEquals(emptyList(), tripMapPoints(record(null, null, null, null)))
        assertEquals(emptyList(), tripMapPoints(record(Double.NaN, 18.6, 54.3, Double.POSITIVE_INFINITY)))
        assertEquals(listOf(Texts.MAP_PLACE), placeMapPoints(54.3721, 18.6381).map { it.label })
        assertEquals(emptyList(), placeMapPoints(null, 18.6))
    }

    @Test
    fun `the map page gets only rounded points in its fragment`() {
        val url = mapPageUrl(listOf(MapPoint("Start", 54.372, 18.638), MapPoint("Koniec", 54.401, 18.6)))
        assertTrue(url.startsWith("file:///android_asset/map/map.html#"))
        assertEquals("54.372,18.638,Start;54.401,18.600,Koniec", android.net.Uri.decode(url.substringAfter('#')))
    }

    @Test
    fun `the map page may load its own assets and OpenStreetMap tiles, nothing else`() {
        fun ok(url: String) = mapRequestAllowed(android.net.Uri.parse(url))
        assertTrue(ok("file:///android_asset/map/leaflet.js"))
        assertTrue(ok("https://tile.openstreetmap.org/16/36000/21000.png"))
        assertTrue(!ok("http://tile.openstreetmap.org/16/1/1.png"))
        assertTrue(!ok("https://evil.example/tile.png"))
        assertTrue(!ok("https://tile.openstreetmap.org.evil.example/1.png"))
        assertTrue(!ok("file:///data/data/pl.lk.toyotacompanion/databases/toyota-companion.db"))
        assertTrue(!ok("file:///android_asset/other/x.html"))
    }

    @Test
    fun `the intent is a VIEW of the geo link`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        openInMap(app, "geo:54.372,18.638?q=54.372,18.638")
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("geo:54.372,18.638?q=54.372,18.638", started.dataString)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, "an application context needs a new task")
    }

    @Test
    fun `anything but a short geo link is refused`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFailsWith<IllegalArgumentException> { openInMap(app, "https://example.com/?q=54.372012345,18.6381234") }
        assertFailsWith<IllegalArgumentException> { openInMap(app, "geo:" + "1".repeat(80)) }
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }
}
