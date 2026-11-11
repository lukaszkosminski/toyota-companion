package pl.lk.toyotacompanion.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.R
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.SummaryDecision
import pl.lk.toyotacompanion.core.domain.SummaryPeriod
import pl.lk.toyotacompanion.core.domain.SummaryWindow
import pl.lk.toyotacompanion.core.domain.periodTotals
import pl.lk.toyotacompanion.core.domain.previousWindow
import pl.lk.toyotacompanion.core.domain.summaryDecision
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.ui.PeriodSummary
import pl.lk.toyotacompanion.ui.PeriodSummaryFormatting
import pl.lk.toyotacompanion.ui.Texts

/**
 * The period summary notification (PRD F54, docs/SPEC.md §54): after a successful synchronization, the last completed week or month is
 * reported once. Only the newest completed period is sent, never the missed ones; the first run and a changed period only remember the key.
 *
 * The lessons of C29 apply: one check at a time under a [Mutex], the key is read inside it, the switch and the permission are checked before
 * anything is posted, the key is written only after a successful post, and the settings, the archive and the post run on [io]. A muted channel
 * does not use up the period. A period with nothing in it is passed over with its key and no notification. Quiet hours (8 to 22) keep the
 * key, so the next daytime synchronization sends it. Nothing here wakes the car or uses the network.
 *
 * @param readTrips / [readExpenses] the archive and the expenses as `(date, amount)` pairs
 * @param period the chosen period, `null` when the summary is off
 * @param lastKey / [markSent] the key of the last summary handled
 * @param clock the local time of the phone; injected for tests
 * @param canPost the permission and the app's notifications; injected for tests
 */
open class PeriodSummaryNotifier(
    private val context: Context,
    private val readTrips: suspend () -> List<StoredTrip>,
    private val readExpenses: suspend () -> List<Pair<LocalDate, BigDecimal>>,
    private val period: () -> SummaryPeriod?,
    private val lastKey: () -> String?,
    private val markSent: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.systemDefault()) },
    private val canPost: () -> Boolean = {
        pl.lk.toyotacompanion.notify.notificationPermissionGranted(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    },
) {
    /** One check at a time: a synchronization and a second one arriving together must not post the same period twice. */
    private val lock = Mutex()

    /** After a successful synchronization; a failed or skipped run says nothing about the period. */
    suspend fun onSyncOutcome(outcome: SyncOutcome) {
        if (outcome !is SyncOutcome.Ok) return
        try {
            lock.withLock {
                val now = clock()
                val decision = withContext(io) { summaryDecision(period(), lastKey(), now.toLocalDate(), now.hour) }
                when (decision) {
                    SummaryDecision.Nothing, SummaryDecision.Wait -> Unit
                    is SummaryDecision.Baseline -> withContext(io) { markSent(decision.key) }
                    is SummaryDecision.Post -> post(decision.window, now.zone)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed summary must not break the synchronization that called it.
        }
    }

    private suspend fun post(window: SummaryWindow, zone: ZoneId) {
        if (!withContext(io) { canPost() }) return
        val trips = withContext(io) { readTrips() }
        val expenses = withContext(io) { readExpenses() }
        val tripList = trips.map { it.record.trip }
        val costs = trips.mapNotNull { it.cost }
        val now = periodTotals(tripList, costs, expenses, window, zone)
        if (now.isEmpty) {
            withContext(io) { markSent(window.key) }
            return
        }
        val before = periodTotals(tripList, costs, expenses, previousWindow(window), zone)
        if (withContext(io) { showSummary(PeriodSummary(window, now, before)) }) {
            withContext(io) { markSent(window.key) }
        }
    }

    /**
     * Posts the summary; `false` when nothing was posted (the channel is muted or there is no permission). Overridable so a test can see it.
     */
    @SuppressLint("MissingPermission")
    open fun showSummary(summary: PeriodSummary): Boolean {
        // canPost checks POST_NOTIFICATIONS and the app's notifications; lint cannot look into the injected lambda (docs/SPEC.md §13.2).
        if (!canPost()) return false
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Texts.PERIOD_SUMMARY_CHANNEL, NotificationManager.IMPORTANCE_LOW),
        )
        // A channel the owner muted counts as "cannot post": the period is not marked as told.
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_refuel)
                .setContentTitle(PeriodSummaryFormatting.title(summary.window))
                .setContentText(PeriodSummaryFormatting.body(summary))
                .setStyle(NotificationCompat.BigTextStyle().bigText(PeriodSummaryFormatting.expanded(summary)))
                .setAutoCancel(true)
                .build(),
        )
        return true
    }

    private companion object {
        const val CHANNEL_ID = "summary"
        const val NOTIFICATION_ID = 902
    }
}
