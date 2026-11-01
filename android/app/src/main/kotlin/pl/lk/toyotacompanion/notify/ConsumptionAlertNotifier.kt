package pl.lk.toyotacompanion.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.R
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.ui.RisingAlert
import pl.lk.toyotacompanion.ui.Texts
import pl.lk.toyotacompanion.ui.consumptionView
import pl.lk.toyotacompanion.ui.risingAlertText
import pl.lk.toyotacompanion.ui.risingConsumptionAlert

/**
 * The "consumption keeps rising" notification (PRD F30, docs/SPEC.md §31): after a successful synchronization the monthly
 * bars are looked at once more, and when three completed months in a row rise outside winter, one notification says so -
 * once per last month, remembered in the settings. A hint to check things like the tyre pressure, not a diagnosis, and it
 * never breaks the synchronization that called it.
 */
open class ConsumptionAlertNotifier(
    private val context: Context,
    private val readTrips: suspend () -> List<StoredTrip>,
    private val lastAlertKey: () -> String?,
    private val markAlerted: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val today: () -> LocalDate = { LocalDate.now() },
    // The permission AND the app's notifications switched on: muted notifications must not use up the month's alert.
    private val canPost: () -> Boolean = {
        notificationPermissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    },
) {
    suspend fun onSyncOutcome(outcome: SyncOutcome) {
        if (outcome !is SyncOutcome.Ok) return
        try {
            val alert = withContext(io) { risingConsumptionAlert(consumptionView(readTrips(), zone()), today()) } ?: return
            if (withContext(io) { lastAlertKey() } == alert.key) return
            // Without the permission nothing is marked, so granting it later still delivers the alert of this month.
            if (!canPost()) return
            if (post(alert)) withContext(io) { markAlerted(alert.key) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A broken alert must not break the synchronization reporting to us.
        }
    }

    /** Posts the notification; `false` when nothing was posted. Overridable so a test can see it. */
    open fun post(alert: RisingAlert): Boolean {
        if (!canPost()) return false
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Texts.ALERT_CHANNEL, NotificationManager.IMPORTANCE_DEFAULT),
        )
        // A channel the owner muted counts as "cannot post": the alert of the month is not marked as told.
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val text = risingAlertText(alert)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_refuel)
                .setContentTitle(Texts.ALERT_TITLE)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
        return true
    }

    private companion object {
        const val CHANNEL_ID = "consumption"
        const val NOTIFICATION_ID = 900
    }
}
