package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.budgetProgress

/** The budget notification rule (PRD F49, docs/SPEC.md §49): 80% and 100%, once per month and threshold. */
class BudgetAlertTest {
    private var previousLanguage = AppLanguage.PL

    @Before
    fun usePolish() {
        previousLanguage = Lang.current
        Lang.current = AppLanguage.PL
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }

    private fun progress(spent: String, limit: String = "800") =
        budgetProgress(BigDecimal(spent), BigDecimal.ZERO, BigDecimal(limit))

    private fun alert(spent: String, limit: String = "800", month: MonthKey = MonthKey(2026, 10), last: String? = null) =
        budgetAlert(progress(spent, limit), month, last)

    @Test
    fun `below_eighty_percent_is_no_alert`() {
        // 600/800 = 75%; 639.99 would round to 80 and is not "below".
        assertNull(alert("600.00"))
        assertNull(alert("0"))
    }

    @Test
    fun `exactly_eighty_percent_warns`() {
        val a = alert("640.00")
        assertEquals(BUDGET_ALERT_WARN_PCT, a?.thresholdPct)
        assertEquals("2026-10:80", a?.key)
    }

    @Test
    fun `ninety_nine_percent_still_warns`() {
        assertEquals(BUDGET_ALERT_WARN_PCT, alert("790.00")?.thresholdPct)
    }

    @Test
    fun `only_spending_above_the_limit_is_the_hundred_percent_alert`() {
        assertEquals(BUDGET_ALERT_OVER_PCT, alert("800.01")?.thresholdPct)
        assertEquals(BUDGET_ALERT_OVER_PCT, alert("850.00")?.thresholdPct)
        assertEquals("2026-10:100", alert("850.00")?.key)
    }

    @Test
    fun `the_limit_itself_and_a_rounded_up_percent_are_still_the_warning`() {
        // 799.60 / 800 = 99.95% rounds to 100%, yet 0,40 zł is left; exactly the limit is not "over" either.
        assertEquals(BUDGET_ALERT_WARN_PCT, alert("799.60")?.thresholdPct)
        assertEquals(BUDGET_ALERT_WARN_PCT, alert("800.00")?.thresholdPct)
        assertEquals("2026-10:80", alert("800.00")?.key)
    }

    @Test
    fun `half_up_of_the_whole_percent_decides_the_eighty`() {
        // 639.99 / 800 = 79.99875% -> 80; 636.00 / 800 = 79.5% -> 80; 635.99 / 800 = 79.4988% -> 79.
        assertEquals(BUDGET_ALERT_WARN_PCT, alert("639.99")?.thresholdPct)
        assertEquals(BUDGET_ALERT_WARN_PCT, alert("636.00")?.thresholdPct)
        assertNull(alert("635.99"))
    }

    @Test
    fun `the_same_threshold_is_told_once_per_month`() {
        assertNull(alert("640.00", last = "2026-10:80"))
        assertNull(alert("790.00", last = "2026-10:80"))
        assertNull(alert("850.00", last = "2026-10:100"))
    }

    @Test
    fun `the_hundred_percent_alert_is_told_after_the_eighty_one`() {
        assertEquals(BUDGET_ALERT_OVER_PCT, alert("850.00", last = "2026-10:80")?.thresholdPct)
    }

    @Test
    fun `the_eighty_percent_warning_is_silent_after_the_hundred_one`() {
        assertNull(alert("640.00", last = "2026-10:100"))
    }

    @Test
    fun `a_new_month_starts_again`() {
        assertEquals(BUDGET_ALERT_WARN_PCT, alert("640.00", month = MonthKey(2026, 11), last = "2026-10:80")?.thresholdPct)
        assertEquals(BUDGET_ALERT_OVER_PCT, alert("850.00", month = MonthKey(2026, 11), last = "2026-10:100")?.thresholdPct)
    }

    @Test
    fun `an_unreadable_key_means_nothing_told_yet`() {
        assertEquals(BUDGET_ALERT_WARN_PCT, alert("640.00", last = "garbage")?.thresholdPct)
        assertEquals(BUDGET_ALERT_WARN_PCT, alert("640.00", last = "2026-10:50")?.thresholdPct)
        assertEquals(BUDGET_ALERT_WARN_PCT, alert("640.00", last = "2026-10")?.thresholdPct)
    }

    @Test
    fun `the_key_has_the_month_and_the_threshold`() {
        assertEquals("2026-10", budgetMonthKey(MonthKey(2026, 10)))
        assertEquals("2026-01", budgetMonthKey(MonthKey(2026, 1)))
    }

    @Test
    fun `the_warning_text_has_the_month_amounts_and_percent`() {
        val a = requireNotNull(alert("640.00"))
        assertEquals("Budżet miesiąca: blisko limitu", budgetAlertTitle(a))
        assertEquals("październik 2026: wydano 640,00 z 800,00 zł (80%). Zostało 160,00 zł.", budgetAlertText(a))
    }

    @Test
    fun `the_warning_text_stays_true_at_the_limit`() {
        val a = requireNotNull(alert("800.00"))
        assertEquals("październik 2026: wydano 800,00 z 800,00 zł (100%). Zostało 0,00 zł.", budgetAlertText(a))
        assertEquals("październik 2026: wydano 799,60 z 800,00 zł (100%). Zostało 0,40 zł.", budgetAlertText(requireNotNull(alert("799.60"))))
    }

    @Test
    fun `the_english_warning_says_what_is_left`() {
        Lang.current = AppLanguage.EN
        assertEquals("October 2026: spent 640,00 of 800,00 zł (80%). Left: 160,00 zł.", budgetAlertText(requireNotNull(alert("640.00"))))
    }

    @Test
    fun `the_exceeded_text_adds_how_much_over`() {
        val a = requireNotNull(alert("850.00"))
        assertEquals("Budżet miesiąca przekroczony", budgetAlertTitle(a))
        assertEquals("październik 2026: wydano 850,00 z 800,00 zł (106%), przekroczono o 50,00 zł.", budgetAlertText(a))
    }

    @Test
    fun `the_alert_texts_carry_no_vin_or_coordinates`() {
        for (a in listOf(requireNotNull(alert("640.00")), requireNotNull(alert("850.00")))) {
            val text = budgetAlertTitle(a) + " " + budgetAlertText(a)
            for (forbidden in listOf("VIN", "54.", "18.", "GPS")) {
                assertTrue(forbidden !in text, "$forbidden in: $text")
            }
        }
    }
}
