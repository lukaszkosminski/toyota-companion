package pl.lk.toyotacompanion.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.MainActivity
import pl.lk.toyotacompanion.R
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.ui.RefuelFormatting
import pl.lk.toyotacompanion.ui.Texts

/**
 * Tells the user about a refuel the moment a synchronization found it
 * (docs/SPEC.md §13.1). `SyncWorker` and "Synchronizuj teraz" both go through the
 * decorated `SyncRunner` in `AppContainer`, so one hook covers the background run
 * and the foreground button.
 *
 * A notification is never worth a crash: every failure inside is swallowed, and a
 * missing `POST_NOTIFICATIONS` permission means "post nothing", not an exception
 * (docs/SPEC.md §13.2).
 *
 * @param pending reads the refuels waiting for a price (`SyncEngine.pendingRefuels`).
 * @param notifiedIds which refuels were already announced; backed by `SettingsStore`.
 * @param markNotified remembers that one refuel was announced; called only after the post.
 * @param forgetNotified drops one id from that set once the entry stops waiting.
 * @param io where the settings file and the database are read — never the UI thread.
 * @param zone zone of the timestamp in the text, the one the refuel list uses too.
 * @param canPost whether posting is allowed at all; injected for tests.
 */
// Open only so a test can make the platform post fail (docs/SPEC.md §13.1).
open class RefuelNotifier(
    private val context: Context,
    private val pending: suspend () -> List<Refuel>,
    private val notifiedIds: () -> Set<String>,
    private val markNotified: (String) -> Unit,
    private val forgetNotified: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val canPost: () -> Boolean = { notificationPermissionGranted(context) },
) {

    /**
     * Call after every synchronization. Only a successful run that detected
     * something can produce a notification, and each waiting entry is announced
     * once — the set in `SettingsStore` survives the process, so a repeated
     * synchronization stays quiet (docs/SPEC.md §13.1).
     */
    suspend fun onSyncOutcome(outcome: SyncOutcome) {
        val ok = outcome as? SyncOutcome.Ok ?: return
        if (ok.refuelsDetected <= 0) return
        // Without the permission nothing is marked either, so granting it later
        // still delivers the notification for whatever arrived in the meantime.
        if (!canPost()) return
        val waiting: List<Refuel> = read { pending() } ?: return
        val seen: Set<String> = read { notifiedIds() } ?: return
        for (refuel in waiting) {
            if (refuel.id in seen) continue
            try {
                // Post first: an entry whose notification never got out — the service
                // failed, or the permission was revoked during this loop — must stay
                // unmarked, so the next run tries it again (docs/SPEC.md §13.1).
                if (post(refuel)) withContext(io) { markNotified(refuel.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A broken notification service must not break the synchronization reporting to us.
            }
        }
    }

    /**
     * Posts one heads-up notification about [refuel]; `false` when nothing was posted
     * because the permission is missing. Overridable so a test can make it fail.
     */
    open fun post(refuel: Refuel): Boolean {
        if (!canPost()) return false
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)
        val text = Texts.refuelNotificationText(
            RefuelFormatting.time(refuel.ts, zone()),
            RefuelFormatting.levelJump(refuel.levelBefore, refuel.levelAfter),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_refuel)
            .setContentTitle(Texts.REFUEL_NOTIFICATION_TITLE)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntentFor(refuel))
            .build()
        manager.notify(notificationId(refuel.id), notification)
        return true
    }

    /**
     * Removes the notification of [refuelId]: when the entry gets its price or is
     * refused from any screen, and when the in-app prompt takes over
     * (docs/SPEC.md §13.1). Forgetting the id keeps the notified set bounded by the
     * number of waiting entries.
     */
    suspend fun cancel(refuelId: String) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId(refuelId))
        } catch (e: Exception) {
            // Cancelling cannot break the write that triggered it.
        }
        try {
            // The settings file is never touched from the UI thread (docs/SPEC.md §12.2).
            withContext(io) { forgetNotified(refuelId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The set is only a reminder; the notification is gone already.
        }
    }

    /**
     * Stable id per entry. `Math.floorMod` because `String.hashCode` can be
     * negative; the same number is the `requestCode` of the pending intent, so two
     * entries never share a notification slot (docs/SPEC.md §13.1).
     */
    fun notificationId(refuelId: String): Int = NOTIFICATION_ID_BASE + Math.floorMod(refuelId.hashCode(), ID_SPAN)

    /** Intent that opens the app on the price form of [refuel] (docs/SPEC.md §13.1). */
    fun intentFor(refuel: Refuel): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REFUEL_ID, refuel.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun pendingIntentFor(refuel: Refuel): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId(refuel.id),
            intentFor(refuel),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun ensureChannel(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Texts.REFUEL_NOTIFICATION_CHANNEL, NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** Reads through [io] and returns null instead of throwing: no reader here is worth a crash. */
    private suspend fun <T> read(body: suspend () -> T): T? =
        try {
            withContext(io) { body() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val CHANNEL_ID = "refuels"
        const val NOTIFICATION_ID_BASE = 1000
        const val ID_SPAN = 100_000
    }
}
