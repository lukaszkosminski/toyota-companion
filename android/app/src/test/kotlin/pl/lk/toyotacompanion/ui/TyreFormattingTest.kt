package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.TyreChange
import pl.lk.toyotacompanion.core.domain.TyreDue
import pl.lk.toyotacompanion.core.domain.TyreReminder
import pl.lk.toyotacompanion.core.domain.TyreSet
import pl.lk.toyotacompanion.core.domain.TyreSetUsage
import pl.lk.toyotacompanion.core.domain.TyreUsage

/** The tyre card and its reminder (PRD F56, docs/SPEC.md §56): the texts, the dates, the months and the state of the card. */
class TyreFormattingTest {
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

    private fun usage(from: String, until: String?, km: String) = TyreSetUsage(
        TyreChange(LocalDate.parse(from), TyreSet.SUMMER, null),
        until?.let(LocalDate::parse),
        12,
        BigDecimal(km),
    )

    @Test
    fun `the_set_on_now_says_its_mileage_and_its_months`() {
        val line = TyreFormatting.changeLine(usage("2026-04-15", null, "2300.0"), LocalDate.parse("2026-11-11"))
        assertEquals("Letni · od 15.04.2026 · 2\u00A0300 km · 6 miesięcy", line)
    }

    @Test
    fun `a_closed_fitting_says_its_range_and_no_months`() {
        val line = TyreFormatting.changeLine(usage("2026-04-15", "2026-10-20", "2300.0"), LocalDate.parse("2026-11-11"))
        assertEquals("Letni · 15.04.2026–20.10.2026 · 2\u00A0300 km", line)
    }

    @Test
    fun `months_are_declined_in_polish_and_english`() {
        assertEquals("1 miesiąc", TyreFormatting.months(1))
        assertEquals("2 miesiące", TyreFormatting.months(2))
        assertEquals("5 miesięcy", TyreFormatting.months(5))
        assertEquals("12 miesięcy", TyreFormatting.months(12))
        assertEquals("22 miesiące", TyreFormatting.months(22))
        Lang.current = AppLanguage.EN
        assertEquals("1 month", TyreFormatting.months(1))
        assertEquals("6 months", TyreFormatting.months(6))
    }

    @Test
    fun `the_dates_are_day_month_year`() {
        assertEquals("01.04.2026", TyreFormatting.day(LocalDate.parse("2026-04-01")))
    }

    @Test
    fun `the_reminder_says_the_set_and_what_is_due`() {
        val u = usage("2026-04-15", null, "12340.0")
        assertEquals("Letni: 6 miesięcy od założenia", TyreFormatting.reminderLine(TyreReminder(u, TyreDue.MONTHS, 6, BigDecimal("12340.0"))))
        assertEquals("Letni: 15\u00A0000 km od założenia", TyreFormatting.reminderLine(TyreReminder(u, TyreDue.KM, 6, BigDecimal("15000"))))
        Lang.current = AppLanguage.EN
        assertEquals("Summer: 6 months since fitting", TyreFormatting.reminderLine(TyreReminder(u, TyreDue.MONTHS, 6, BigDecimal("12340.0"))))
    }

    @Test
    fun `the_trips_before_the_first_change_are_named_and_not_assigned`() {
        assertEquals("Przed pierwszą zmianą (bez kompletu): 12 tras · 30 km", TyreFormatting.beforeFirst(BigDecimal("30.0"), 12))
        assertEquals("Przed pierwszą zmianą (bez kompletu): 1 trasa · 5 km", TyreFormatting.beforeFirst(BigDecimal("5.0"), 1))
        assertEquals("Przed pierwszą zmianą (bez kompletu): 592 trasy · 4\u00A0887 km", TyreFormatting.beforeFirst(BigDecimal("4887.0"), 592))
        Lang.current = AppLanguage.EN
        assertEquals("Before the first change (no set): 2 trips · 5 km", TyreFormatting.beforeFirst(BigDecimal("5.0"), 2))
    }

    @Test
    fun `an_empty_card_invites_the_first_change_and_a_loading_one_says_so`() {
        assertEquals(TyreCardMode.LOADING, tyreCardMode(TyreUiState(loading = true)))
        assertEquals(TyreCardMode.INVITE, tyreCardMode(TyreUiState(loading = false)))
        val withChange = TyreUiState(
            loading = false,
            changes = listOf(TyreChange(LocalDate.parse("2026-04-15"), TyreSet.SUMMER, null)),
            usage = TyreUsage(emptyList(), 0, BigDecimal.ZERO),
        )
        assertEquals(TyreCardMode.SETS, tyreCardMode(withChange))
        assertEquals(TyreCardMode.INVITE, tyreCardMode(withChange.copy(usage = null)))
    }

    @Test
    fun `the_tyre_texts_differ_between_the_languages_and_carry_no_vin`() {
        fun all() = listOf(
            Texts.TYRE_TITLE, Texts.TYRE_HINT, Texts.TYRE_EMPTY, Texts.TYRE_CHANGE_BUTTON, Texts.TYRE_FIRST_BUTTON,
            Texts.TYRE_THRESHOLDS_BUTTON, Texts.TYRE_REMINDER_TITLE, Texts.TYRE_ERR_FUTURE, Texts.tyreSetName(TyreSet.WINTER),
        )
        val polish = all()
        Lang.current = AppLanguage.EN
        val english = all()
        for ((pl, en) in polish.zip(english)) {
            assertFalse("VIN" in pl + en, pl)
            assertFalse(pl == en, "untranslated: $pl")
        }
    }

    @Test
    fun `the_removal_names_the_fitting_and_says_its_reminders_go`() {
        val text = Texts.tyreRemoveBody("Zimowy", "11.11.2026")
        assertTrue(text.contains("Zimowy z 11.11.2026"), text)
        assertTrue(text.contains("zostaną zapomniane"), text)
    }
}
