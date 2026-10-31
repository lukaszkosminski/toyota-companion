package pl.lk.toyotacompanion.notify

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager
import pl.lk.toyotacompanion.MainActivity
import pl.lk.toyotacompanion.ui.Texts
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.sync.SyncOutcome

private val TS: Instant = Instant.parse("2026-10-30T13:05:00Z")

private fun refuel(id: String, before: Int = 20, after: Int = 80): Refuel = Refuel(
    id = id,
    ts = TS,
    odometerKm = 12_345.0,
    levelBefore = before,
    levelAfter = after,
    liters = null,
    pricePerLiter = null,
    totalCost = null,
    status = RefuelStatus.PENDING,
)

private fun ok(refuelsDetected: Int): SyncOutcome = SyncOutcome.Ok(
    tripsAdded = 3,
    readingsAdded = 1,
    refuelsDetected = refuelsDetected,
    tripsCosted = 3,
    telemetryFailed = false,
    state = SyncState(),
)

/**
 * The notification of a detected refuel (docs/SPEC.md §13.1). Robolectric with
 * `ShadowNotificationManager`: what matters here is what really lands in the
 * notification manager, not a fake of it.
 */
@RunWith(RobolectricTestRunner::class)
class RefuelNotifierTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private val waiting = mutableListOf<Refuel>()
    private val notified = linkedSetOf<String>()
    private var allowed = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        waiting.clear()
        notified.clear()
        allowed = true
    }

    private fun notifier(): RefuelNotifier = RefuelNotifier(
        context = context,
        pending = { waiting.toList() },
        notifiedIds = { notified.toSet() },
        markNotified = { id -> notified.add(id) },
        forgetNotified = { id -> notified.remove(id) },
        io = Dispatchers.Unconfined,
        zone = { ZoneId.of("Europe/Warsaw") },
        canPost = { allowed },
    )

    private fun shadow(): ShadowNotificationManager = shadowOf(manager)

    @Test
    fun `a waiting refuel is announced once`() = runTest {
        waiting.add(refuel("r1"))

        notifier().onSyncOutcome(ok(refuelsDetected = 1))

        assertEquals(1, shadow().allNotifications.size)
        val note = shadow().allNotifications[0]
        assertEquals("refuels", note.channelId)
        assertNotNull(manager.getNotificationChannel("refuels"), "channel created on first post")
        assertEquals(Texts.REFUEL_NOTIFICATION_TITLE, shadowOf(note).contentTitle.toString())
    }

    @Test
    fun `the next synchronization does not announce the same entry again`() = runTest {
        waiting.add(refuel("r1"))
        val notifier = notifier()

        notifier.onSyncOutcome(ok(refuelsDetected = 1))
        notifier.onSyncOutcome(ok(refuelsDetected = 0))

        assertEquals(1, shadow().allNotifications.size, "no duplicate, no matter how often we sync")
    }

    @Test
    fun `a run that found nothing announces nothing`() = runTest {
        waiting.add(refuel("r1"))
        val notifier = notifier()

        notifier.onSyncOutcome(ok(refuelsDetected = 0))
        notifier.onSyncOutcome(SyncOutcome.Skipped)
        notifier.onSyncOutcome(SyncOutcome.Failed(pl.lk.toyotacompanion.core.sync.SyncError(
            pl.lk.toyotacompanion.core.data.SyncErrorKind.NETWORK,
            "network",
        ), SyncState()))

        assertEquals(0, shadow().allNotifications.size)
        assertEquals(emptySet<String>(), notified, "nothing is remembered as announced")
    }

    @Test
    fun `without the permission nothing is sent and nothing is remembered`() = runTest {
        waiting.add(refuel("r1"))
        allowed = false
        val notifier = notifier()

        notifier.onSyncOutcome(ok(refuelsDetected = 1))

        assertEquals(0, shadow().allNotifications.size)
        assertEquals(emptySet<String>(), notified)

        // The user granted the permission later: the next run that detects something
        // still announces the entry that waited (nothing was marked while it could not be sent).
        allowed = true
        notifier.onSyncOutcome(ok(refuelsDetected = 1))
        assertEquals(1, shadow().allNotifications.size)
    }

    @Test
    fun `the text carries the time and the levels and nothing else`() = runTest {
        waiting.add(refuel("r1", before = 20, after = 80))

        notifier().onSyncOutcome(ok(refuelsDetected = 1))

        val text = shadowOf(shadow().allNotifications[0]).contentText.toString()
        assertEquals("30.10 14:05 · 20% → 80%. Podaj cenę za litr.", text)
        assertTrue(!text.contains("km") && !text.contains("12"), "no odometer (docs/SECURITY.md): $text")
    }

    @Test
    fun `the intent opens the app with the id of this refuel`() {
        val intent = notifier().intentFor(refuel("r7"))

        assertEquals("r7", intent.getStringExtra(MainActivity.EXTRA_REFUEL_ID))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `cancel removes the notification and forgets the id`() = runTest {
        val notifier = notifier()
        waiting.add(refuel("r1"))
        notifier.onSyncOutcome(ok(refuelsDetected = 1))
        val id = notifier.notificationId("r1")

        notifier.cancel("r1")

        assertEquals(0, shadow().allNotifications.size)
        assertEquals(emptySet<String>(), notified)
        assertTrue(id >= 1000, "id stays in the block reserved for the app")
    }

    @Test
    fun `two entries never share a notification slot`() {
        val notifier = notifier()

        assertTrue(notifier.notificationId("r1") != notifier.notificationId("r2"))
        // hashCode of a String can be negative; the id may not.
        assertTrue(notifier.notificationId("-8a9c1b2e") >= 1000)
    }

    @Test
    fun `an entry whose post failed is not marked as notified`() = runTest {
        waiting.add(refuel("r1"))
        var attempts = 0
        val broken = object : RefuelNotifier(
            context = context,
            pending = { waiting.toList() },
            notifiedIds = { notified.toSet() },
            markNotified = { id -> notified.add(id) },
            forgetNotified = { id -> notified.remove(id) },
            io = Dispatchers.Unconfined,
            zone = { ZoneId.of("Europe/Warsaw") },
            canPost = { allowed },
        ) {
            override fun post(refuel: Refuel): Boolean {
                attempts++
                throw IllegalStateException("notification service is dead")
            }
        }

        broken.onSyncOutcome(ok(refuelsDetected = 1))

        assertEquals(1, attempts, "the failure is swallowed, the synchronization is not broken")
        assertEquals(emptySet<String>(), notified, "never delivered, so never announced")

        // The same entry is announced by the next run, once the service works again.
        notifier().onSyncOutcome(ok(refuelsDetected = 1))
        assertEquals(1, shadow().allNotifications.size)
        assertEquals(setOf("r1"), notified)
    }

    @Test
    fun `a permission revoked during the loop marks only what was delivered`() = runTest {
        // Two entries wait; the newest is posted, and the user takes the permission away
        // before the second one. What did not leave the app may not be remembered as
        // announced, or the next run would never send it (SPEC 13.1).
        waiting.add(refuel("r1", before = 30, after = 94))
        waiting.add(refuel("r2", before = 10, after = 20))
        var calls = 0
        val notifier = RefuelNotifier(
            context = context,
            pending = { waiting.toList() },
            notifiedIds = { notified.toSet() },
            markNotified = { id -> notified.add(id) },
            forgetNotified = { id -> notified.remove(id) },
            io = Dispatchers.Unconfined,
            zone = { ZoneId.of("Europe/Warsaw") },
            // Call 1 is the guard of onSyncOutcome, call 2 the post of r1; after that the
            // permission is gone and every post must report false.
            canPost = { ++calls <= 2 },
        )

        notifier.onSyncOutcome(ok(refuelsDetected = 2))

        assertEquals(1, shadow().allNotifications.size, "r2 could not be posted")
        val text = shadowOf(shadow().allNotifications[0]).contentText.toString()
        assertTrue("94%" in text, "the delivered one is r1: $text")
        assertEquals(listOf("r1"), notified.toList(), "only what was delivered is remembered")
    }

}
