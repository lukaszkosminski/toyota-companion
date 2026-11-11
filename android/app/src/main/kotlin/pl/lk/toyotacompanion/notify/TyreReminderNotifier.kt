package pl.lk.toyotacompanion.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import pl.lk.toyotacompanion.core.domain.SUMMARY_FROM_HOUR
import pl.lk.toyotacompanion.core.domain.SUMMARY_UNTIL_HOUR
import pl.lk.toyotacompanion.core.domain.TyreReminder
import pl.lk.toyotacompanion.core.domain.dueTyreReminders
import pl.lk.toyotacompanion.core.domain.formatTyreReminderKeys
import pl.lk.toyotacompanion.core.domain.parseTyreChanges
import pl.lk.toyotacompanion.core.domain.parseTyreReminderKeys
import pl.lk.toyotacompanion.core.domain.parseTyreThresholds
import pl.lk.toyotacompanion.core.domain.tyreReminderKey
import pl.lk.toyotacompanion.core.domain.tyreUsage
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.ui.TyreFormatting
import pl.lk.toyotacompanion.ui.Texts

/**
 * The tyre reminder (PRD F56, docs/SPEC.md §56): after a successful synchronization, the set on now is checked against its thresholds, and a
 * reminder is posted once per set, fitting day and kind. Counted from the fitting day, never from the weather.
 *
 * The lessons of C29 apply: one check at a time under a [Mutex], the key is written only after a successful post, the settings, the archive and
 * the post run on [io], a muted channel does not use up the reminder, and quiet hours (8 to 22) keep it for the next daytime synchronization.
 * Nothing here wakes the car or uses the network.
 *
 * @param readChangesText / [readThresholdsText] / [readKeysText] the settings as text (parsed by the core); [saveKeysText] keeps the sent keys
 * @param clock the local time of the phone; injected for tests
 * @param canPost the permission and the app's notifications; injected for tests
 */
open class TyreReminderNotifier(
    private val context: Context,
    private val readTrips: suspend () -> List<StoredTrip>,
    private val readChangesText: () -> String?,
    private val readThresholdsText: () -> String?,
    private val readKeysText: () -> String?,
    private val saveKeysText: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.systemDefault()) },
    private val canPost: () -> Boolean = {
        notificationPermissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    },
) {
    /** One check at a time: two synchronizations arriving together must not post the same reminder twice. */
    private val lock = Mutex()

    /** After a successful synchronization; a failed or skipped run says nothing about the tyres. */
    suspend fun onSyncOutcome(outcome: SyncOutcome) {
        if (outcome !is SyncOutcome.Ok) return
        try {
            lock.withLock {
                val now = clock()
                if (now.hour < SUMMARY_FROM_HOUR || now.hour >= SUMMARY_UNTIL_HOUR) return@withLock
                val trips = withContext(io) { readTrips() }
                val due = withContext(io) {
                    val usage = tyreUsage(parseTyreChanges(readChangesText()), trips.map { it.record.trip }, now.zone)
                    dueTyreReminders(usage, parseTyreThresholds(readThresholdsText()), now.toLocalDate())
                }
                val sent = withContext(io) { parseTyreReminderKeys(readKeysText()) }
                val fresh = due.filter { tyreReminderKey(it) !in sent }
                if (fresh.isEmpty()) return@withLock
                if (!withContext(io) { canPost() }) return@withLock
                if (withContext(io) { showReminder(fresh) }) {
                    withContext(io) { saveKeysText(formatTyreReminderKeys(sent + fresh.map { tyreReminderKey(it) })) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed reminder must not break the synchronization that called it.
        }
    }

    /** Posts one notification with the reminders of this check; `false` when nothing was posted. Overridable so a test can see it. */
    @SuppressLint("MissingPermission")
    open fun showReminder(reminders: List<TyreReminder>): Boolean {
        // canPost checks POST_NOTIFICATIONS and the app's notifications (docs/SPEC.md §13.2).
        if (!canPost()) return false
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, Texts.TYRE_REMINDER_CHANNEL, NotificationManager.IMPORTANCE_LOW))
        // A channel the owner muted counts as "cannot post": the reminder is not marked as told.
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val lines = reminders.map { TyreFormatting.reminderLine(it) }
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_refuel)
                .setContentTitle(Texts.TYRE_REMINDER_TITLE)
                .setContentText(lines.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                .setAutoCancel(true)
                .build(),
        )
        return true
    }

    private companion object {
        const val CHANNEL_ID = "tyres"
        const val NOTIFICATION_ID = 903
    }
}
