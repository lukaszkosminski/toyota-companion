package pl.lk.toyotacompanion.widget

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.R
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.ui.consumptionView

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun month(month: Int, l100: Double): StoredTrip {
    val begin = Instant.parse("2026-${month.toString().padStart(2, '0')}-10T10:00:00Z")
    return StoredTrip(TripRecord(Trip("m$month", begin, begin.plusSeconds(3_600), 100.0, BigDecimal(l100.toString()), null), "VINAAA111"), null)
}

/** docs/SPEC.md §32: the lines of the widget, their store and the views built from them. */
@RunWith(RobolectricTestRunner::class)
class WidgetSnapshotTest {
    private val view = consumptionView(listOf(month(5, 4.0), month(6, 4.4), month(7, 4.8), month(8, 5.2)), WARSAW)

    @Test
    fun `the main line is the average, the second the trend and the price, the third the forecast`() {
        val snapshot = widgetSnapshot(view, "Tankowanie za ok. 30 dni", BigDecimal("6.3"))
        assertEquals("4,60 l/100 km", snapshot.main)
        assertTrue(snapshot.line2.startsWith("↗ rosnące (+"), snapshot.line2)
        assertTrue(snapshot.line2.endsWith("cena 6,30 zł/l"), snapshot.line2)
        assertEquals("Tankowanie za ok. 30 dni", snapshot.line3)
    }

    @Test
    fun `missing pieces stay empty and no consumption is an empty snapshot`() {
        val noPrice = widgetSnapshot(view, null, null)
        assertEquals("", noPrice.line3)
        assertFalse(noPrice.line2.contains("cena"))
        val empty = widgetSnapshot(consumptionView(emptyList(), WARSAW), "x", BigDecimal("6"))
        assertTrue(empty.isEmpty)
        assertTrue(widgetSnapshot(null, null, null).isEmpty)
    }

    @Test
    fun `the store keeps the lines and logout clears them`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = WidgetSnapshotStore(context)
        store.save(WidgetSnapshot("4,60 l/100 km", "a", "b"))
        assertEquals(WidgetSnapshot("4,60 l/100 km", "a", "b"), WidgetSnapshotStore(context).load())
        store.clear()
        assertTrue(store.load().isEmpty)
    }

    @Test
    fun `the views carry the lines, hide an empty one and say open the app without data`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val full = ConsumptionWidgetProvider.views(context, WidgetSnapshot("4,60 l/100 km", "→ stabilne", ""))
        val root = full.apply(context, FrameLayout(context))
        assertEquals("4,60 l/100 km", root.findViewById<TextView>(R.id.widget_main).text.toString())
        assertEquals("→ stabilne", root.findViewById<TextView>(R.id.widget_line2).text.toString())
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_line3).visibility)
        val empty = ConsumptionWidgetProvider.views(context, WidgetSnapshot("", "", "")).apply(context, FrameLayout(context))
        assertEquals("Otwórz aplikację", empty.findViewById<TextView>(R.id.widget_main).text.toString())
    }

    @Test
    fun `a snapshot carries no position and no vin`() {
        val snapshot = widgetSnapshot(view, "Tankowanie za ok. 30 dni", BigDecimal("6.3"))
        val all = snapshot.main + snapshot.line2 + snapshot.line3
        assertFalse(all.contains("VIN", ignoreCase = true) || all.contains("54."))
    }
}
