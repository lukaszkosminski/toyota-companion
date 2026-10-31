package pl.lk.toyotacompanion.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

}
