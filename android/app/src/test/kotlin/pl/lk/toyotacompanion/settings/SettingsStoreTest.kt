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
    fun `monthly_budget_round_trips_and_garbage_reads_as_null`() {
        assertNull(store.monthlyBudget(), "bez klucza")
        store.saveMonthlyBudget(BigDecimal("800.50"))
        assertEquals(0, BigDecimal("800.50").compareTo(requireNotNull(store.monthlyBudget())))
        assertEquals("800.50", prefs.getString("monthly_budget", null), "toPlainString, always a dot")

        store.saveMonthlyBudget(null)
        assertNull(store.monthlyBudget())
        assertNull(prefs.getString("monthly_budget", null))

        for (text in listOf("", "   ", "abc", "0", "-5", "0.004")) {
            prefs.edit().putString("monthly_budget", text).commit()
            assertNull(store.monthlyBudget(), "dla [$text]")
        }
    }

    @Test
    fun `monthly_budget_of_the_wrong_type_reads_as_null`() {
        prefs.edit().putInt("monthly_budget", 800).commit()
        assertNull(store.monthlyBudget())
    }

    @Test
    fun `the budget alert switch is on by default and round_trips`() {
        assertTrue(store.budgetAlert())
        store.saveBudgetAlert(false)
        assertFalse(store.budgetAlert())
        assertEquals(false, prefs.getBoolean("budget_alert", true))
        store.saveBudgetAlert(true)
        assertTrue(store.budgetAlert())
    }

    @Test
    fun `the last budget alert key round_trips`() {
        assertNull(store.lastBudgetAlert())
        store.saveLastBudgetAlert("2026-10:80")
        assertEquals("2026-10:80", store.lastBudgetAlert())
        store.saveLastBudgetAlert("2026-10:100")
        assertEquals("2026-10:100", store.lastBudgetAlert())
    }

    @Test
    fun `the budget alert keys of the wrong type read as their defaults`() {
        prefs.edit().putString("budget_alert", "off").putInt("budget_alert_last", 80).commit()
        assertTrue(store.budgetAlert(), "zly typ = domyslnie wlaczone")
        assertNull(store.lastBudgetAlert(), "zly typ = nic nie powiedziano")
        store.saveBudgetAlert(false)
        store.saveLastBudgetAlert("2026-10:80")
        assertFalse(store.budgetAlert())
        assertEquals("2026-10:80", store.lastBudgetAlert())
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
    fun `eco goals round_trip and null removes only its goal`() {
        assertNull(store.ecoGoalConsumption())
        assertNull(store.ecoGoalEvPct())

        store.saveEcoGoals(BigDecimal("4.50"), 40)
        assertEquals(0, BigDecimal("4.50").compareTo(requireNotNull(store.ecoGoalConsumption())))
        assertEquals(40, store.ecoGoalEvPct())
        assertEquals("4.50", prefs.getString("eco_goal_consumption", null))

        store.saveEcoGoals(null, 40)
        assertNull(store.ecoGoalConsumption())
        assertEquals(40, store.ecoGoalEvPct())
        store.saveEcoGoals(null, null)
        assertNull(store.ecoGoalEvPct())
    }

    @Test
    fun `garbage eco goals read as no goal`() {
        for (text in listOf("abc", "0", "-1", "20.01", "")) {
            prefs.edit().putString("eco_goal_consumption", text).commit()
            assertNull(store.ecoGoalConsumption(), text)
        }
        prefs.edit().putInt("eco_goal_ev_pct", 101).commit()
        assertNull(store.ecoGoalEvPct())
        prefs.edit().putInt("eco_goal_ev_pct", 0).commit()
        assertNull(store.ecoGoalEvPct())
        prefs.edit().remove("eco_goal_ev_pct").putString("eco_goal_ev_pct", "40").commit()
        assertNull(store.ecoGoalEvPct(), "a string under the key is not a goal")
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
