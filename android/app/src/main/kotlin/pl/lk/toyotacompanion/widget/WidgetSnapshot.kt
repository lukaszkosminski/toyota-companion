package pl.lk.toyotacompanion.widget

import android.content.Context
import java.math.BigDecimal
import pl.lk.toyotacompanion.ui.ConsumptionView
import pl.lk.toyotacompanion.ui.Texts
import pl.lk.toyotacompanion.ui.TripsFormatting

/**
 * What the home-screen widget shows (docs/SPEC.md §32): three short lines. A copy of numbers the status screen already
 * shows, kept in a private preference file so the widget never reads the archive or the network itself; no position, no VIN.
 */
data class WidgetSnapshot(val main: String, val line2: String, val line3: String) {
    val isEmpty: Boolean get() = main.isEmpty()
}

/**
 * The snapshot of the owner's numbers: the whole-archive average (main line), the trend or the default price (second) and the
 * refuel forecast (third). Lines without data stay empty; no consumption at all gives an empty snapshot, which the widget
 * shows as "open the app".
 */
fun widgetSnapshot(view: ConsumptionView?, forecastLine: String?, defaultPrice: BigDecimal?): WidgetSnapshot {
    val overall = view?.overall ?: return WidgetSnapshot("", "", "")
    val main = Texts.widgetMain(TripsFormatting.plain(overall, 2))
    val trend = pl.lk.toyotacompanion.ui.ConsumptionFormatting.trendLine(view)
    val price = defaultPrice?.let { Texts.widgetPrice(TripsFormatting.plain(it, 2)) }
    val line2 = listOfNotNull(trend?.removePrefix("Trend: "), price).joinToString(Texts.SEPARATOR)
    return WidgetSnapshot(main, line2, forecastLine.orEmpty())
}

/** The snapshot in a private preference file; written by the app after a synchronization, read by the widget. */
class WidgetSnapshotStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): WidgetSnapshot = WidgetSnapshot(
        prefs.getString(MAIN, "").orEmpty(),
        prefs.getString(LINE2, "").orEmpty(),
        prefs.getString(LINE3, "").orEmpty(),
    )

    fun save(snapshot: WidgetSnapshot) {
        prefs.edit().putString(MAIN, snapshot.main).putString(LINE2, snapshot.line2).putString(LINE3, snapshot.line3).apply()
    }

    /** Logout: the widget must not keep showing an account's numbers. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "widget_snapshot"
        const val MAIN = "main"
        const val LINE2 = "line2"
        const val LINE3 = "line3"
    }
}
