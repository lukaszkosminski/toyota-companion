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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.R
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.ui.BudgetAlert
import pl.lk.toyotacompanion.ui.Expense
import pl.lk.toyotacompanion.ui.MonthKey
import pl.lk.toyotacompanion.ui.MonthlyBudget
import pl.lk.toyotacompanion.ui.Texts
import pl.lk.toyotacompanion.ui.budgetAlert
import pl.lk.toyotacompanion.ui.budgetAlertText
import pl.lk.toyotacompanion.ui.budgetAlertTitle
import pl.lk.toyotacompanion.ui.budgetProgressFor

/**
 * The monthly budget notification (PRD F49, docs/SPEC.md §49): one warning at 80% of the limit and one when it is
 * exceeded (spent > limit), once per month and threshold. Triggered after a successful synchronization and after an expense was added or removed;
 * never on simply opening the status screen (the card is there) and never after changing the limit.
 *
 * The channel is "budget" (`IMPORTANCE_DEFAULT`); a muted channel counts as "cannot post" and does not use up the
 * month's threshold. Without the runtime permission, with notifications switched off or with the owner's budget switch
 * off nothing is marked either, so granting or enabling them later still delivers the alert of this month. The checks
 * run one at a time (a sync and an expense change can arrive together), so the key never goes down and nothing is posted twice. A notification is never worth a crash: every failure
 * inside is swallowed (docs/SPEC.md §13.1, §13.2).
 *
 * @param readLimit the monthly limit from the settings, `null` when there is none
 * @param readTrips / [readExpenses] the local archive and the expenses table, the same reads as the budget card
 * @param lastAlertKey the stored `RRRR-MM:80|100`, `null` when nothing was told
 * @param markAlerted remembers a posted key; called only after [post] returned true
 * @param alertEnabled the owner's budget-alert switch (Options); read on [io]
 * @param io where the settings, the archive, the permission checks and the post run — never the UI thread
 * @param zone the zone of the month, the same as the budget card
 * @param today the day the current month is taken from; injected for tests
 * @param canPost permission AND notifications switched on; injected for tests
 */
open class BudgetAlertNotifier(
    private val context: Context,
    private val readLimit: () -> BigDecimal?,
    private val readTrips: suspend () -> List<StoredTrip>,
    private val readExpenses: suspend () -> List<Expense>,
    private val lastAlertKey: () -> String?,
    private val markAlerted: (String) -> Unit,
    private val alertEnabled: () -> Boolean = { true },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
    private val canPost: () -> Boolean = {
        notificationPermissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    },
) {
    /** One check at a time: the last key is read inside it, so a slower trigger sees what a faster one marked. */
    private val lock = Mutex()

    /** After a successful synchronization; a failed or skipped run says nothing about the month. */
    suspend fun onSyncOutcome(outcome: SyncOutcome) {
        if (outcome !is SyncOutcome.Ok) return
        check()
    }

    /** After an expense was added or removed; the card's figures change, so the thresholds are looked at again. */
    suspend fun onExpensesChanged() = check()

    private suspend fun check() {
        try {
            lock.withLock {
                val day = today()
                val month = MonthKey(day.year, day.monthValue)
                val limit = withContext(io) { readLimit() } ?: return@withLock
                val progress = withContext(io) {
                    budgetProgressFor(MonthlyBudget(limit), readTrips(), readExpenses(), zone(), month)
                } ?: return@withLock
                withContext(io) {
                    val alert = budgetAlert(progress, month, lastAlertKey()) ?: return@withContext
                    // Without the switch/permission nothing is marked, so enabling it later still delivers this month's alert.
                    if (!alertEnabled() || !canPost()) return@withContext
                    if (post(alert)) markAlerted(alert.key)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A broken alert must not break the synchronization or the expense write that called it.
        }
    }

    /** Posts the notification; `false` when nothing was posted. Overridable so a test can see it. */
    // [canPost] checks POST_NOTIFICATIONS and the channel; lint cannot look into the injected lambda. A post without
    // the permission on API 33+ is dropped silently, never thrown (docs/SPEC.md §13.2).
    @SuppressLint("MissingPermission")
    open fun post(alert: BudgetAlert): Boolean {
        if (!canPost()) return false
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Texts.BUDGET_ALERT_CHANNEL, NotificationManager.IMPORTANCE_DEFAULT),
        )
        // A channel the owner muted counts as "cannot post": the month's threshold is not marked as told.
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val text = budgetAlertText(alert)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_refuel)
                .setContentTitle(budgetAlertTitle(alert))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
        return true
    }

    private companion object {
        const val CHANNEL_ID = "budget"
        const val NOTIFICATION_ID = 901
    }
}
