package pl.lk.toyotacompanion.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
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

}
