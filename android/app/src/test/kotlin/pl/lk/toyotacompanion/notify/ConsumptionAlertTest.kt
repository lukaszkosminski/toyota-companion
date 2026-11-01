package pl.lk.toyotacompanion.notify

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import pl.lk.toyotacompanion.ui.Texts
import pl.lk.toyotacompanion.ui.consumptionView
import pl.lk.toyotacompanion.ui.risingAlertText
import pl.lk.toyotacompanion.ui.risingConsumptionAlert

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/** One 100 km trip of a month at [l100] litres per 100 km. */
private fun month(year: Int, month: Int, l100: Double): StoredTrip {
    val begin = Instant.parse("$year-${month.toString().padStart(2, '0')}-10T10:00:00Z")
    return StoredTrip(
        TripRecord(Trip("m$year-$month", begin, begin.plusSeconds(3_600), 100.0, BigDecimal(l100.toString()), null), "VINAAA111"),
        null,
    )
}

private fun alertFor(vararg months: Pair<Pair<Int, Int>, Double>, today: LocalDate) =
    risingConsumptionAlert(consumptionView(months.map { month(it.first.first, it.first.second, it.second) }, WARSAW), today)

private fun ok(): SyncOutcome = SyncOutcome.Ok(1, 0, 0, 1, false, SyncState())

/** docs/SPEC.md §31: three months of rising consumption outside winter, told once. */
@RunWith(RobolectricTestRunner::class)
class ConsumptionAlertTest {
    private lateinit var context: Context
    private var trips: List<StoredTrip> = emptyList()
    private var last: String? = null
    private var allowed = true
    private var today = LocalDate.parse("2026-08-05")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        trips = listOf(month(2026, 5, 4.0), month(2026, 6, 4.3), month(2026, 7, 4.7))
        last = null
        allowed = true
        today = LocalDate.parse("2026-08-05")
    }

    private fun notifier() = ConsumptionAlertNotifier(
        context = context,
        readTrips = { trips },
        lastAlertKey = { last },
        markAlerted = { last = it },
        io = Dispatchers.Unconfined,
        zone = { WARSAW },
        today = { today },
        canPost = { allowed },
    )

    private fun posted() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Test
    fun `three rising completed months outside winter make an alert`() {
        val alert = assertNotNull(alertFor((2026 to 5) to 4.0, (2026 to 6) to 4.3, (2026 to 7) to 4.7, today = LocalDate.parse("2026-08-05")))
        assertEquals("2026-07", alert.key)
        assertEquals(BigDecimal("17.5"), alert.totalPct)
        assertEquals("l/100 km: 4,0 → 4,3 → 4,7 (+18 %). Poza zimą warto sprawdzić np. ciśnienie w oponach. To tylko podpowiedź, aplikacja nie diagnozuje.", risingAlertText(alert))
    }

    @Test
    fun `a step under three percent, a total under eight, a gap or a fall is no alert`() {
        val t = LocalDate.parse("2026-08-05")
        assertNull(alertFor((2026 to 5) to 4.0, (2026 to 6) to 4.05, (2026 to 7) to 4.5, today = t))
        assertNull(alertFor((2026 to 5) to 4.0, (2026 to 6) to 4.13, (2026 to 7) to 4.26, today = t))
        assertNull(alertFor((2026 to 4) to 4.0, (2026 to 6) to 4.3, (2026 to 7) to 4.7, today = t))
        assertNull(alertFor((2026 to 5) to 4.0, (2026 to 6) to 4.7, (2026 to 7) to 4.4, today = t))
    }

    @Test
    fun `a step of 2,96 percent is no alert even though it rounds to three`() {
        // 6.77 -> 6.97 is +2.954 %, which the old one-decimal rounding turned into 3.0; the second step is large.
        assertNull(alertFor((2026 to 5) to 6.77, (2026 to 6) to 6.97, (2026 to 7) to 7.6, today = LocalDate.parse("2026-08-05")))
        // 6.77 -> 6.98 is +3.10 %: the gate passes.
        assertNotNull(alertFor((2026 to 5) to 6.77, (2026 to 6) to 6.98, (2026 to 7) to 7.6, today = LocalDate.parse("2026-08-05")))
    }

    @Test
    fun `the month in progress is left out and winter gives no alert`() {
        // Today is in July: July is not finished, so only May and June remain - too few.
        assertNull(alertFor((2026 to 5) to 4.0, (2026 to 6) to 4.3, (2026 to 7) to 4.7, today = LocalDate.parse("2026-07-20")))
        // The last of the three is December.
        assertNull(alertFor((2025 to 10) to 4.0, (2025 to 11) to 4.3, (2025 to 12) to 4.7, today = LocalDate.parse("2026-01-05")))
        // November is outside March-October as well.
        assertNull(alertFor((2025 to 9) to 4.0, (2025 to 10) to 4.3, (2025 to 11) to 4.7, today = LocalDate.parse("2025-12-05")))
    }

    @Test
    fun `a rising trend is announced once and then stays quiet`() = runTest {
        notifier().onSyncOutcome(ok())
        assertEquals(1, posted().size)
        val note = posted().single()
        assertEquals("consumption", note.channelId)
        assertEquals(Texts.ALERT_TITLE, shadowOf(note).contentTitle.toString())
        assertEquals("2026-07", last)
        notifier().onSyncOutcome(ok())
        assertEquals(1, posted().size)
        // A new month with another rise is a new alert.
        trips = trips + month(2026, 8, 5.2)
        today = LocalDate.parse("2026-09-03")
        notifier().onSyncOutcome(ok())
        // One slot: the new alert replaces the old one instead of piling up.
        assertEquals(1, posted().size)
        assertEquals("2026-08", last)
    }

    @Test
    fun `without the permission nothing is posted or marked, a failed sync does nothing`() = runTest {
        allowed = false
        notifier().onSyncOutcome(ok())
        assertEquals(0, posted().size)
        assertNull(last)
        allowed = true
        notifier().onSyncOutcome(SyncOutcome.Skipped)
        assertEquals(0, posted().size)
        notifier().onSyncOutcome(ok())
        assertEquals(1, posted().size)
    }

    @Test
    fun `muted notifications of the app do not use up the alert of the month`() = runTest {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(false)
        val muted = ConsumptionAlertNotifier(
            context = context,
            readTrips = { trips },
            lastAlertKey = { last },
            markAlerted = { last = it },
            io = Dispatchers.Unconfined,
            zone = { WARSAW },
            today = { today },
        )
        muted.onSyncOutcome(ok())
        assertNull(last)
        shadowOf(manager).setNotificationsEnabled(true)
        muted.onSyncOutcome(ok())
        assertEquals("2026-07", last)
    }

    @Test
    fun `a muted channel is not marked as told`() = runTest {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(android.app.NotificationChannel("consumption", "Spalanie", NotificationManager.IMPORTANCE_NONE))
        notifier().onSyncOutcome(ok())
        assertEquals(0, posted().size)
        assertNull(last)
    }

    @Test
    fun `a broken read does not break the synchronization`() = runTest {
        val broken = ConsumptionAlertNotifier(
            context = context,
            readTrips = { throw IllegalStateException("db") },
            lastAlertKey = { last },
            markAlerted = { last = it },
            io = Dispatchers.Unconfined,
            today = { today },
            canPost = { true },
        )
        broken.onSyncOutcome(ok())
        assertEquals(0, posted().size)
        assertTrue(true)
    }
}
