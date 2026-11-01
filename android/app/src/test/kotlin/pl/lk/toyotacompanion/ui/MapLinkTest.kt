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
    fun `a trip with both ends offers start then end, rounded to three decimals`() {
        val links = tripMapLinks(record(54.37249, 18.63849, 54.40051, 18.60049))
        assertEquals(listOf(TripMapEnd.START, TripMapEnd.END), links.map { it.end })
        assertEquals("geo:54.372,18.638?q=54.372,18.638", links[0].uri)
        assertEquals("geo:54.401,18.600?q=54.401,18.600", links[1].uri)
        for (link in links) assertTrue(Regex("\\.\\d{4,}").find(link.uri) == null, link.uri)
    }

    @Test
    fun `only the ends with a position are offered and none means no window`() {
        assertEquals(listOf(TripMapEnd.START), tripMapLinks(record(54.3, 18.6, null, null)).map { it.end })
        assertEquals(listOf(TripMapEnd.END), tripMapLinks(record(null, null, 54.3, 18.6)).map { it.end })
        assertEquals(emptyList(), tripMapLinks(record(null, null, null, null)))
        assertEquals(emptyList(), tripMapLinks(record(Double.NaN, 18.6, 54.3, Double.POSITIVE_INFINITY)))
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
