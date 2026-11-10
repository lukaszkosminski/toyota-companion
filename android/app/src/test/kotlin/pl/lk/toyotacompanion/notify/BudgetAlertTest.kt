package pl.lk.toyotacompanion.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.ui.AppLanguage
import pl.lk.toyotacompanion.ui.Expense
import pl.lk.toyotacompanion.ui.ExpenseCategory
import pl.lk.toyotacompanion.ui.Lang
import pl.lk.toyotacompanion.ui.Texts

private val IN_MONTH: Instant = Instant.parse("2026-10-15T10:00:00Z")

private val IN_NOVEMBER: Instant = Instant.parse("2026-11-02T10:00:00Z")

private fun trip(id: String, cost: String, at: Instant = IN_MONTH): StoredTrip = StoredTrip(
    record = TripRecord(
        trip = Trip(id, at, at.plusSeconds(1_800), 12.3, BigDecimal("0.600"), null),
        vin = "VINAAA111",
    ),
    cost = TripCost(id, BigDecimal(cost), BigDecimal("6.19"), "r1", CostSource.REFUEL),
)

private fun expense(id: String, amount: String): Expense =
    Expense(id, LocalDate.of(2026, 10, 5), ExpenseCategory.SERVICE, BigDecimal(amount), null)

private fun ok(): SyncOutcome = SyncOutcome.Ok(1, 0, 0, 1, false, SyncState())

/** PRD F49, docs/SPEC.md §49: 80% and 100%, once per month and threshold, channel "budget". */
@RunWith(RobolectricTestRunner::class)
class BudgetAlertTest {
    private lateinit var context: Context
    private var limit: BigDecimal? = BigDecimal("800")
    private var trips: List<StoredTrip> = listOf(trip("a", "640.00"))
    private var expenses: List<Expense> = emptyList()
    @Volatile private var last: String? = null
    private var allowed = true
    private var enabled = true
    private var previousLanguage = AppLanguage.PL
    private var today = LocalDate.parse("2026-10-15")

    @Before
    fun setUp() {
        previousLanguage = Lang.current
        Lang.current = AppLanguage.PL
        context = ApplicationProvider.getApplicationContext()
        limit = BigDecimal("800")
        trips = listOf(trip("a", "640.00"))
        expenses = emptyList()
        last = null
        allowed = true
        enabled = true
        today = LocalDate.parse("2026-10-15")
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }

    private fun notifier() = BudgetAlertNotifier(
        context = context,
        readLimit = { limit },
        readTrips = { trips },
        readExpenses = { expenses },
        lastAlertKey = { last },
        markAlerted = { last = it },
        alertEnabled = { enabled },
        io = Dispatchers.Unconfined,
        zone = { ZoneOffset.UTC },
        today = { today },
        canPost = { allowed },
    )

    private fun posted() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Test
    fun `eighty_percent_posts_on_the_budget_channel_and_remembers_the_month`() = runTest {
        notifier().onSyncOutcome(ok())

        assertEquals(1, posted().size)
        val note = posted().single()
        assertEquals("budget", note.channelId)
        assertEquals(Texts.BUDGET_ALERT_WARN_TITLE, shadowOf(note).contentTitle.toString())
        assertEquals("2026-10:80", last)
    }

    @Test
    fun `the_body_is_polish_and_carries_no_vin`() = runTest {
        notifier().onSyncOutcome(ok())

        val note = posted().single()
        val expected = "październik 2026: wydano 640,00 z 800,00 zł (80%). Zostało 160,00 zł."
        assertEquals(expected, note.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(expected, note.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        val all = shadowOf(note).contentTitle.toString() + expected
        assertFalse("VINAAA111" in all, all)
    }

    @Test
    fun `the_exceeded_body_says_how_much_over`() = runTest {
        trips = listOf(trip("a", "850.00"))
        notifier().onSyncOutcome(ok())

        val note = posted().single()
        assertEquals("2026-10:100", last)
        assertEquals(
            "październik 2026: wydano 850,00 z 800,00 zł (106%), przekroczono o 50,00 zł.",
            note.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun `exactly_the_limit_is_only_the_warning`() = runTest {
        trips = listOf(trip("a", "800.00"))
        notifier().onSyncOutcome(ok())

        assertEquals("2026-10:80", last)
        assertEquals(Texts.BUDGET_ALERT_WARN_TITLE, shadowOf(posted().single()).contentTitle.toString())
        // Spending past the limit afterwards is still told, because only the 80 was used up.
        trips = listOf(trip("a", "800.01"))
        notifier().onExpensesChanged()
        assertEquals("2026-10:100", last)
        assertEquals(Texts.BUDGET_ALERT_OVER_TITLE, shadowOf(posted().single()).contentTitle.toString())
    }

    @Test
    fun `a_repeated_sync_stays_quiet`() = runTest {
        val n = notifier()
        n.onSyncOutcome(ok())
        n.onSyncOutcome(ok())

        assertEquals(1, posted().size)
        assertEquals("2026-10:80", last)
    }

    @Test
    fun `the_hundred_percent_alert_follows_the_eighty_one`() = runTest {
        val n = notifier()
        n.onSyncOutcome(ok())
        assertEquals("2026-10:80", last)

        trips = listOf(trip("a", "850.00"))
        n.onExpensesChanged()

        // One budget notification id: the 100% alert replaces the 80% one on screen.
        assertEquals(1, posted().size)
        assertEquals("2026-10:100", last)
        assertEquals(Texts.BUDGET_ALERT_OVER_TITLE, shadowOf(posted().single()).contentTitle.toString())
    }

    @Test
    fun `without_permission_nothing_is_posted_or_marked`() = runTest {
        allowed = false
        val n = notifier()
        n.onSyncOutcome(ok())

        assertEquals(0, posted().size)
        assertNull(last)

        // Granting it later delivers the alert of the month, because nothing was marked.
        allowed = true
        n.onSyncOutcome(ok())
        assertEquals(1, posted().size)
        assertEquals("2026-10:80", last)
    }

    @Test
    fun `a_failed_or_skipped_sync_posts_nothing`() = runTest {
        val n = notifier()
        n.onSyncOutcome(SyncOutcome.Failed(pl.lk.toyotacompanion.core.sync.SyncError(pl.lk.toyotacompanion.core.data.SyncErrorKind.NETWORK, "offline"), SyncState()))
        n.onSyncOutcome(SyncOutcome.Skipped)

        assertEquals(0, posted().size)
        assertNull(last)
    }

    @Test
    fun `a_muted_channel_is_not_marked_as_told`() = runTest {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("budget", "Budżet", NotificationManager.IMPORTANCE_NONE))

        notifier().onSyncOutcome(ok())

        assertEquals(0, posted().size)
        assertNull(last)
    }

    @Test
    fun `a_new_month_starts_again`() = runTest {
        val n = notifier()
        n.onSyncOutcome(ok())
        assertEquals("2026-10:80", last)

        today = LocalDate.parse("2026-11-01")
        trips = listOf(trip("b", "640.00", IN_NOVEMBER))
        n.onSyncOutcome(ok())

        assertEquals("2026-11:80", last)
        assertEquals(Texts.BUDGET_ALERT_WARN_TITLE, shadowOf(posted().single()).contentTitle.toString())
    }

    @Test
    fun `without_a_budget_nothing_is_posted`() = runTest {
        limit = null
        notifier().onSyncOutcome(ok())

        assertEquals(0, posted().size)
        assertNull(last)
    }

    @Test
    fun `a_broken_read_does_not_break_the_sync`() = runTest {
        val broken = BudgetAlertNotifier(
            context = context,
            readLimit = { limit },
            readTrips = { throw IllegalStateException("db") },
            readExpenses = { expenses },
            lastAlertKey = { last },
            markAlerted = { last = it },
            io = Dispatchers.Unconfined,
            zone = { ZoneOffset.UTC },
            today = { today },
            canPost = { allowed },
        )

        broken.onSyncOutcome(ok())

        assertEquals(0, posted().size)
        assertNull(last)
    }

    @Test
    fun `after_the_hundred_the_eighty_and_the_hundred_are_silent`() = runTest {
        val n = notifier()
        trips = listOf(trip("a", "850.00"))
        n.onSyncOutcome(ok())
        assertEquals("2026-10:100", last)
        val firstTitle = shadowOf(posted().single()).contentTitle.toString()

        // The same state again: silence for the 100.
        n.onSyncOutcome(ok())
        // An expense deleted brings the month back to 80%: silence for the 80, the key does not go down.
        trips = listOf(trip("a", "640.00"))
        n.onExpensesChanged()

        assertEquals("2026-10:100", last)
        assertEquals(1, posted().size)
        assertEquals(firstTitle, shadowOf(posted().single()).contentTitle.toString())
    }

    @Test
    fun `a_switched_off_budget_alert_posts_and_marks_nothing_until_it_is_on`() = runTest {
        enabled = false
        val n = notifier()
        n.onSyncOutcome(ok())
        n.onExpensesChanged()

        assertEquals(0, posted().size)
        assertNull(last)

        enabled = true
        n.onExpensesChanged()
        assertEquals(1, posted().size)
        assertEquals("2026-10:80", last)
    }

    @Test
    fun `a_muted_channel_delivers_after_it_is_unmuted`() = runTest {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("budget", "Budżet", NotificationManager.IMPORTANCE_NONE))
        val n = notifier()
        n.onSyncOutcome(ok())
        assertEquals(0, posted().size)
        assertNull(last)

        // The owner turns the channel back on in the system settings.
        manager.getNotificationChannel("budget").importance = NotificationManager.IMPORTANCE_DEFAULT
        n.onSyncOutcome(ok())

        assertEquals(1, posted().size)
        assertEquals("2026-10:80", last)
    }

    @Test
    fun `a_broken_expense_read_does_not_break_the_sync`() = runTest {
        val broken = BudgetAlertNotifier(
            context = context,
            readLimit = { limit },
            readTrips = { trips },
            readExpenses = { throw IllegalStateException("db") },
            lastAlertKey = { last },
            markAlerted = { last = it },
            io = Dispatchers.Unconfined,
            zone = { ZoneOffset.UTC },
            today = { today },
            canPost = { allowed },
        )

        broken.onSyncOutcome(ok())
        broken.onExpensesChanged()

        assertEquals(0, posted().size)
        assertNull(last)
    }

    @Test
    fun `the_month_is_taken_from_one_reading_of_today`() = runTest {
        var reads = 0
        val n = BudgetAlertNotifier(
            context = context,
            readLimit = { limit },
            readTrips = { listOf(trip("d", "640.00", Instant.parse("2026-12-31T23:59:00Z"))) },
            readExpenses = { expenses },
            lastAlertKey = { last },
            markAlerted = { last = it },
            io = Dispatchers.Unconfined,
            zone = { ZoneOffset.UTC },
            // The first reading is still December, a second one would already be January.
            today = { reads++; if (reads == 1) LocalDate.parse("2026-12-31") else LocalDate.parse("2027-01-01") },
            canPost = { allowed },
        )

        n.onSyncOutcome(ok())

        assertEquals("2026-12:80", last)
    }

    /** A sync and an expense change arriving together: one post, and the key never goes down. */
    @Test
    fun `two_triggers_at_once_post_once`() = runBlocking {
        val inFirstPost = CountDownLatch(1)
        val release = CountDownLatch(1)
        var posts = 0
        val n = object : BudgetAlertNotifier(
            context = context,
            readLimit = { limit },
            readTrips = { trips },
            readExpenses = { expenses },
            lastAlertKey = { last },
            markAlerted = { last = it },
            io = Dispatchers.IO,
            zone = { ZoneOffset.UTC },
            today = { today },
            canPost = { allowed },
        ) {
            override fun post(alert: pl.lk.toyotacompanion.ui.BudgetAlert): Boolean {
                val mine = synchronized(this) { ++posts }
                if (mine == 1) {
                    inFirstPost.countDown()
                    // Without the lock the second trigger gets here while this one is still inside.
                    release.await(2, TimeUnit.SECONDS)
                }
                return super.post(alert)
            }
        }

        val first = launch(Dispatchers.Default) { n.onSyncOutcome(ok()) }
        assertTrue(inFirstPost.await(5, TimeUnit.SECONDS))
        val second = launch(Dispatchers.Default) { n.onExpensesChanged() }
        delay(300)
        release.countDown()
        joinAll(first, second)

        assertEquals(1, posts)
        assertEquals("2026-10:80", last)
        assertEquals(1, posted().size)
    }
}
