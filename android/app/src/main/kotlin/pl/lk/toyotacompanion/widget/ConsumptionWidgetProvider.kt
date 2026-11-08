package pl.lk.toyotacompanion.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import pl.lk.toyotacompanion.MainActivity
import pl.lk.toyotacompanion.R
import pl.lk.toyotacompanion.ui.Texts

/**
 * The home-screen widget (PRD F31, docs/SPEC.md §32): average consumption, the trend or the default price and the refuel
 * forecast, from the snapshot the app saved. It asks nobody for anything; a tap opens the app.
 */
class ConsumptionWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = WidgetSnapshotStore(context).load()
        for (id in appWidgetIds) manager.updateAppWidget(id, views(context, snapshot))
    }

    companion object {
        /** Repaints every placed widget from the saved snapshot; safe to call without any widget placed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ConsumptionWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val snapshot = WidgetSnapshotStore(context).load()
            for (id in ids) manager.updateAppWidget(id, views(context, snapshot))
        }

        internal fun views(context: Context, snapshot: WidgetSnapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_consumption)
            views.setTextViewText(R.id.widget_title, Texts.WIDGET_TITLE)
            views.setTextViewText(R.id.widget_main, if (snapshot.isEmpty) Texts.WIDGET_EMPTY else snapshot.main)
            views.setTextViewText(R.id.widget_line2, snapshot.line2)
            views.setTextViewText(R.id.widget_line3, snapshot.line3)
            views.setViewVisibility(R.id.widget_line2, if (snapshot.line2.isEmpty()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_line3, if (snapshot.line3.isEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widget_line4, snapshot.line4)
            views.setViewVisibility(R.id.widget_line4, if (snapshot.line4.isEmpty()) View.GONE else View.VISIBLE)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }
    }
}
