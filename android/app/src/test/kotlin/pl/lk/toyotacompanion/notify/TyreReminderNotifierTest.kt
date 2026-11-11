package pl.lk.toyotacompanion.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.sync.SyncOutcome

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun ok(): SyncOutcome = SyncOutcome.Ok(1, 0, 0, 1, false, SyncState())

/** The tyre reminder (PRD F56): one per set, fitting day and kind; quiet hours, a muted channel and a repeated check. */
@RunWith(RobolectricTestRunner::class)
class TyreReminderNotifierTest {
    private lateinit var context: Context
    private var changes: String? = "2026-04-15|S|"
    private var thresholds: String? = "S:6:"
    private var keys: String? = null
    private var now: ZonedDateTime = ZonedDateTime.of(2026, 10, 16, 10, 0, 0, 0, WARSAW)
    private var allowed = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        changes = "2026-04-15|S|"
        thresholds = "S:6:"
        keys = null
        now = ZonedDateTime.of(2026, 10, 16, 10, 0, 0, 0, WARSAW)
        allowed = true
    }

    private fun notifier() = TyreReminderNotifier(
        context = context,
        readTrips = { listOf(StoredTrip(TripRecord(Trip("a", Instant.parse("2026-05-02T07:00:00Z"), Instant.parse("2026-05-02T07:15:00Z"), 10.0, BigDecimal("0.5"), null), "VINAAA111"), null)) },
        readChangesText = { changes },
        readThresholdsText = { thresholds },
        readKeysText = { keys },
        saveKeysText = { keys = it },
        io = Dispatchers.Unconfined,
        clock = { now },
        canPost = { allowed },
    )

    private fun posted() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Test
    fun `the_reminder_is_posted_once_on_the_tyres_channel_and_its_key_is_kept`() = runTest {
        notifier().onSyncOutcome(ok())
        notifier().onSyncOutcome(ok())

        assertEquals(1, posted().size)
        assertEquals("tyres", posted().single().channelId)
        assertEquals("SUMMER:2026-04-15:MONTHS", keys)
    }

    @Test
    fun `quiet_hours_post_nothing_and_keep_no_key`() = runTest {
        now = ZonedDateTime.of(2026, 10, 16, 7, 0, 0, 0, WARSAW)
        notifier().onSyncOutcome(ok())

        assertTrue(posted().isEmpty())
        assertEquals(null, keys)
    }

    @Test
    fun `a_muted_channel_does_not_use_up_the_reminder`() = runTest {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("tyres", "Opony", NotificationManager.IMPORTANCE_NONE))
        notifier().onSyncOutcome(ok())

        assertTrue(posted().isEmpty())
        assertEquals(null, keys)
    }

    @Test
    fun `without_a_threshold_nothing_is_posted`() = runTest {
        thresholds = null
        notifier().onSyncOutcome(ok())

        assertTrue(posted().isEmpty())
    }

    @Test
    fun `a_failed_sync_says_nothing`() = runTest {
        notifier().onSyncOutcome(SyncOutcome.Skipped)

        assertTrue(posted().isEmpty())
    }
}
