package pl.lk.toyotacompanion.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.SummaryPeriod
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.sync.SyncOutcome
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.ui.AppLanguage
import pl.lk.toyotacompanion.ui.Lang
import pl.lk.toyotacompanion.ui.Texts

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/** A trip of 15 minutes in the local day of [day] (`2026-11-03`), 10 km with the given litres. */
private fun trip(id: String, day: String, km: Double = 10.0, fuel: String = "0.6"): StoredTrip {
    val start = Instant.parse("${day}T07:00:00Z")
    return StoredTrip(TripRecord(Trip(id, start, start.plusSeconds(900), km, BigDecimal(fuel), null), "VINAAA111"), null)
}

private fun ok(): SyncOutcome = SyncOutcome.Ok(1, 0, 0, 1, false, SyncState())

/** The period summary (PRD F54, docs/SPEC.md §54), with the same fixtures style as BudgetAlertTest. */
@RunWith(RobolectricTestRunner::class)
class PeriodSummaryNotifierTest {
    private lateinit var context: Context
    private var period: SummaryPeriod? = SummaryPeriod.WEEK
    private var last: String? = "week:2026-10-26"
    private var allowed = true
    private var trips: List<StoredTrip> = listOf(trip("a", "2026-11-03"), trip("b", "2026-11-05"))
    private var expenses: List<Pair<LocalDate, BigDecimal>> = emptyList()
    private var now: ZonedDateTime = ZonedDateTime.of(2026, 11, 10, 10, 0, 0, 0, WARSAW)
    private var previousLanguage = AppLanguage.PL

    @Before
    fun setUp() {
        previousLanguage = Lang.current
        Lang.current = AppLanguage.PL
        context = ApplicationProvider.getApplicationContext()
        period = SummaryPeriod.WEEK
        last = "week:2026-10-26"
        allowed = true
        trips = listOf(trip("a", "2026-11-03"), trip("b", "2026-11-05"))
        expenses = emptyList()
        now = ZonedDateTime.of(2026, 11, 10, 10, 0, 0, 0, WARSAW)
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }

    private fun notifier(readTrips: suspend () -> List<StoredTrip> = { trips }) = PeriodSummaryNotifier(
        context = context,
        readTrips = readTrips,
        readExpenses = { expenses },
        period = { period },
        lastKey = { last },
        markSent = { last = it },
        io = Dispatchers.Unconfined,
        clock = { now },
        canPost = { allowed },
    )

    private fun posted() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Test
    fun `the_last_completed_week_is_posted_once_on_the_summary_channel`() = runTest {
        val n = notifier()
        n.onSyncOutcome(ok())
        n.onSyncOutcome(ok())

        assertEquals(1, posted().size)
        val note = posted().single()
        assertEquals("summary", note.channelId)
        assertEquals("Tydzień 2–8.11", shadowOf(note).contentTitle.toString())
        assertEquals("week:2026-11-02", last)
    }

    @Test
    fun `the_expanded_text_has_the_split_and_the_comparison_with_the_week_before`() = runTest {
        trips = trips + trip("prev", "2026-10-28")
        expenses = listOf(LocalDate.of(2026, 11, 4) to BigDecimal("17.10"))
        notifier().onSyncOutcome(ok())

        val expanded = posted().single().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(expanded.contains("Paliwo brak ceny · wydatki 17 zł"), expanded)
        assertTrue(expanded.contains("Wobec poprzedniego tygodnia: km +"), expanded)
    }

    @Test
    fun `the_first_run_only_remembers_the_period`() = runTest {
        last = null
        notifier().onSyncOutcome(ok())

        assertTrue(posted().isEmpty())
        assertEquals("week:2026-11-02", last)
    }

    @Test
    fun `quiet_hours_post_nothing_and_mark_nothing`() = runTest {
        now = ZonedDateTime.of(2026, 11, 10, 7, 59, 0, 0, WARSAW)
        notifier().onSyncOutcome(ok())
        now = ZonedDateTime.of(2026, 11, 10, 22, 0, 0, 0, WARSAW)
        notifier().onSyncOutcome(ok())

        assertTrue(posted().isEmpty())
        assertEquals("week:2026-10-26", last)
        now = ZonedDateTime.of(2026, 11, 10, 8, 0, 0, 0, WARSAW)
        notifier().onSyncOutcome(ok())
        assertEquals(1, posted().size)
    }

    @Test
    fun `switched_off_posts_nothing_and_marks_nothing`() = runTest {
        period = null
        notifier().onSyncOutcome(ok())

        assertTrue(posted().isEmpty())
        assertEquals("week:2026-10-26", last)
    }

    @Test
    fun `a_muted_channel_does_not_use_up_the_period`() = runTest {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("summary", "Podsumowanie", NotificationManager.IMPORTANCE_NONE))
        notifier().onSyncOutcome(ok())

        assertTrue(posted().isEmpty())
        assertEquals("week:2026-10-26", last)
    }

    @Test
    fun `without_permission_nothing_is_marked_and_granting_it_later_sends_the_period`() = runTest {
        allowed = false
        notifier().onSyncOutcome(ok())
        assertEquals("week:2026-10-26", last)

        allowed = true
        notifier().onSyncOutcome(ok())
        assertEquals(1, posted().size)
        assertEquals("week:2026-11-02", last)
    }

    @Test
    fun `a_period_with_nothing_in_it_is_passed_over_with_its_key`() = runTest {
        trips = emptyList()
        notifier().onSyncOutcome(ok())

        assertTrue(posted().isEmpty())
        assertEquals("week:2026-11-02", last)
    }

    @Test
    fun `a_period_with_only_an_expense_is_reported_with_its_cost`() = runTest {
        trips = emptyList()
        expenses = listOf(LocalDate.of(2026, 11, 4) to BigDecimal("120.00"))
        notifier().onSyncOutcome(ok())

        assertEquals("0 km · 120 zł", posted().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `a_failed_read_breaks_nothing_and_marks_nothing`() = runTest {
        notifier(readTrips = { throw IllegalStateException("db") }).onSyncOutcome(ok())

        assertTrue(posted().isEmpty())
        assertEquals("week:2026-10-26", last)
    }

    @Test
    fun `a_month_is_reported_for_the_previous_month`() = runTest {
        period = SummaryPeriod.MONTH
        trips = listOf(trip("oct", "2026-10-12"))
        now = ZonedDateTime.of(2026, 11, 1, 10, 0, 0, 0, WARSAW)
        notifier().onSyncOutcome(ok())

        assertEquals("Październik 2026", shadowOf(posted().single()).contentTitle.toString())
        assertEquals("month:2026-10", last)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(Texts.PERIOD_SUMMARY_CHANNEL, manager.getNotificationChannel("summary").name)
    }
}
