package pl.lk.toyotacompanion.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.ui.MonthKey

/**
 * The unencrypted settings file (docs/SPEC.md §12.2). Robolectric, like
 * [pl.lk.toyotacompanion.security.EncryptedSecretStoreTest], because the class is
 * about what really lands in SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("test-settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SettingsStore(prefs)
    }

    @Test
    fun `price_round_trips_as_plain_text`() {
        store.saveDefaultPrice(BigDecimal("6.190"))

        assertEquals(0, BigDecimal("6.19").compareTo(requireNotNull(store.defaultPrice())))
        assertEquals("6.190", prefs.getString("default_price", null), "toPlainString, always a dot")
    }

    @Test
    fun `garbage_reads_as_null`() {
        for (text in listOf("", "   ", "abc", "6,19,20", "six")) {
            prefs.edit().putString("default_price", text).commit()
            assertNull(store.defaultPrice(), "dla [$text]")
        }
        prefs.edit().clear().commit()
        assertNull(store.defaultPrice(), "bez klucza")
    }

    @Test
    fun `clear_removes_the_price`() {
        store.saveDefaultPrice(BigDecimal("6.19"))
        store.clearDefaultPrice()

        assertNull(store.defaultPrice())
        assertNull(prefs.getString("default_price", null))
    }

    @Test
    fun `the automatic copy folder and day round trip, and garbage reads as none`() {
        assertNull(store.autoBackupFolder())
        assertNull(store.autoBackupLastDay())
        store.saveAutoBackupFolder("content://tree/home")
        store.saveAutoBackupLastDay(java.time.LocalDate.parse("2026-11-11"))
        assertEquals("content://tree/home", store.autoBackupFolder())
        assertEquals(java.time.LocalDate.parse("2026-11-11"), store.autoBackupLastDay())

        store.saveAutoBackupFolder(null)
        store.saveAutoBackupLastDay(null)
        assertNull(store.autoBackupFolder())
        assertNull(store.autoBackupLastDay())

        prefs.edit().putInt("auto_backup_folder", 7).putString("auto_backup_last", "yesterday").commit()
        assertNull(store.autoBackupFolder(), "zly typ = brak folderu")
        assertNull(store.autoBackupLastDay(), "zly typ = brak daty")
        prefs.edit().remove("auto_backup_last").putLong("auto_backup_last", 9_999_999_999L).commit()
        assertNull(store.autoBackupLastDay(), "liczba spoza zakresu = brak daty")
    }

    @Test
    fun `nothing_is_stored_before_the_first_save`() {
        assertNotNull(prefs.all)
        assertEquals(0, prefs.all.size, "pusty plik to brak ceny, nie blad")
        assertNull(store.defaultPrice())
    }

    @Test
    fun `a price that is not positive reads as null`() {
        for (text in listOf("0", "0.000", "-5", "-0.001")) {
            prefs.edit().putString("default_price", text).commit()
            assertNull(store.defaultPrice(), "dla [$text]")
        }
    }


    @Test
    fun `notified refuel ids round trip and can be forgotten`() {
        store.markRefuelNotified("r1")
        store.markRefuelNotified("r2")

        assertEquals(setOf("r1", "r2"), store.notifiedRefuelIds())
        assertNotNull(prefs.getString("refuel_notified_ids", null), "plain ids, not a secret")

        store.forgetNotifiedRefuel("r1")
        assertEquals(setOf("r2"), store.notifiedRefuelIds())

        store.forgetNotifiedRefuel("r2")
        assertEquals(emptySet<String>(), store.notifiedRefuelIds())
        assertNull(prefs.getString("refuel_notified_ids", null), "an empty set leaves no garbage key")
    }

    @Test
    fun `the last copy has no date before the first export`() {
        assertNull(store.lastArchiveExport(), "never exported is not an error (docs/SPEC.md 14.3)")
    }

    @Test
    fun `a last copy key holding text reads as never`() {
        prefs.edit().putString("last_archive_export", "garbage").commit()

        assertNull(store.lastArchiveExport(), "a foreign value under the key must not crash the status screen")
    }

    @Test
    fun `the last copy date round trips and the newer copy wins`() {
        val first = Instant.parse("2026-10-31T06:00:00.123Z")

        store.saveLastArchiveExport(first)

        assertEquals(first, store.lastArchiveExport())
        assertEquals(
            first.toEpochMilli(),
            prefs.getLong("last_archive_export", -1L),
            "epoch millis, and the key survives logging out (it belongs to the phone)",
        )

        val second = Instant.parse("2026-11-01T06:00:00Z")
        store.saveLastArchiveExport(second)

        assertEquals(second, store.lastArchiveExport())
    }

    @Test
    fun `the permission question is remembered as asked`() {
        assertEquals(false, store.notificationsAsked(), "first login asks (docs/SPEC.md 13.2)")

        store.markNotificationsAsked()

        assertEquals(true, store.notificationsAsked())
    }

    @Test
    fun `the_period_summary_round_trips_and_a_change_drops_the_last_key`() {
        assertNull(store.periodSummaryKind())
        store.savePeriodSummaryKind(pl.lk.toyotacompanion.core.domain.SummaryPeriod.WEEK)
        assertEquals(pl.lk.toyotacompanion.core.domain.SummaryPeriod.WEEK, store.periodSummaryKind())
        store.saveLastPeriodSummary("week:2026-11-02")
        store.savePeriodSummaryKind(pl.lk.toyotacompanion.core.domain.SummaryPeriod.WEEK)
        assertEquals("week:2026-11-02", store.lastPeriodSummary(), "the same choice keeps the key")
        store.savePeriodSummaryKind(pl.lk.toyotacompanion.core.domain.SummaryPeriod.MONTH)
        assertNull(store.lastPeriodSummary(), "a new period starts from a baseline")
        store.savePeriodSummaryKind(null)
        assertNull(store.periodSummaryKind())
    }

    @Test
    fun `the_tyre_texts_round_trip_and_start_empty`() {
        assertNull(store.tyreChangesText())
        store.saveTyreChangesText("2026-04-15|S|1234")
        assertEquals("2026-04-15|S|1234", store.tyreChangesText())
        store.saveTyreThresholdsText("S:6:15000;W::")
        assertEquals("S:6:15000;W::", store.tyreThresholdsText())
        store.saveTyreReminderKeysText("SUMMER:2026-04-15:MONTHS")
        assertEquals("SUMMER:2026-04-15:MONTHS", store.tyreReminderKeysText())
    }
}
