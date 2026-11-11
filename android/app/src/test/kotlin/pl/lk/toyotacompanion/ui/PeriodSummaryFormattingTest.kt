package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.PeriodTotals
import pl.lk.toyotacompanion.core.domain.SummaryPeriod
import pl.lk.toyotacompanion.core.domain.SummaryWindow

/** The period summary text (PRD F54, docs/SPEC.md §54): the title, the short line, the expanded text and the comparison. */
class PeriodSummaryFormattingTest {
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

    private val week = SummaryWindow(SummaryPeriod.WEEK, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9))
    private val month = SummaryWindow(SummaryPeriod.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))

    private fun totals(
        km: String = "412.0",
        fuel: String? = "41.20",
        expenses: String = "17.10",
        lPer100: String? = "4.60",
        ev: String? = "38.0",
        trips: Int = 9,
    ) = PeriodTotals(
        trips = trips,
        distanceKm = BigDecimal(km),
        fuelL = BigDecimal("50.0"),
        fuelCostPln = fuel?.let(::BigDecimal),
        expensesPln = BigDecimal(expenses),
        lPer100Km = lPer100?.let(::BigDecimal),
        evSharePct = ev?.let(::BigDecimal),
    )

    @Test
    fun `the_week_title_names_the_days_and_the_month_the_month`() {
        assertEquals("Tydzień 2–8.11", PeriodSummaryFormatting.title(week))
        assertEquals("Październik 2026", PeriodSummaryFormatting.title(month))
        // A week over a month boundary names both months.
        val across = SummaryWindow(SummaryPeriod.WEEK, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2))
        assertEquals("Tydzień 26.10–1.11", PeriodSummaryFormatting.title(across))
    }

    @Test
    fun `the_short_line_has_km_whole_zloty_litres_and_the_share`() {
        val summary = PeriodSummary(week, totals(), totals(km = "300.0"))
        assertEquals("412 km · 58 zł · 4,6 l/100 km · 38 % na prądzie", PeriodSummaryFormatting.body(summary))
    }

    @Test
    fun `the_litres_and_the_share_are_left_out_when_there_is_no_value`() {
        val summary = PeriodSummary(week, totals(lPer100 = null, ev = null, trips = 0, km = "0.0"), totals())
        assertEquals("0 km · 58 zł", PeriodSummaryFormatting.body(summary))
    }

    @Test
    fun `the_whole_zloty_add_up_from_the_rounded_parts`() {
        // Fuel 41.40 and expenses 17.40 are 58.80: the parts round to 41 and 17, so the total shown is 58, not 59.
        val summary = PeriodSummary(week, totals(fuel = "41.40", expenses = "17.40"), totals())
        assertTrue(PeriodSummaryFormatting.body(summary).contains("58 zł"))
        assertTrue(PeriodSummaryFormatting.expanded(summary).contains("Paliwo 41 zł · wydatki 17 zł"))
    }

    @Test
    fun `the_expanded_text_has_the_split_and_the_comparison`() {
        val summary = PeriodSummary(week, totals(), totals(km = "366.0", fuel = "45.00", expenses = "12.00"))
        val text = PeriodSummaryFormatting.expanded(summary).split("\n")
        assertEquals("412 km · 58 zł · 4,6 l/100 km · 38 % na prądzie", text[0])
        assertEquals("Paliwo 41 zł · wydatki 17 zł", text[1])
        // 412 against 366 is +13 %; 58 against 57 is +2 %.
        assertEquals("Wobec poprzedniego tygodnia: km +13 %, koszt +2 %", text[2])
    }

    @Test
    fun `without_a_price_the_fuel_says_so_and_without_a_previous_period_there_is_no_comparison`() {
        val summary = PeriodSummary(month, totals(fuel = null, trips = 9), totals(trips = 0, km = "0.0", fuel = null, expenses = "0.00", ev = null, lPer100 = null))
        val text = PeriodSummaryFormatting.expanded(summary).split("\n")
        assertEquals("Paliwo brak ceny · wydatki 17 zł", text[1])
        assertEquals(2, text.size)
        assertNull(PeriodSummaryFormatting.versus(summary))
    }

    @Test
    fun `the_texts_read_in_both_languages`() {
        Lang.current = AppLanguage.EN
        assertEquals("Week 2–8.11", PeriodSummaryFormatting.title(week))
        assertEquals("October 2026", PeriodSummaryFormatting.title(month))
        assertEquals("412 km · 58 zł · 4.6 l/100 km · 38 % on battery", PeriodSummaryFormatting.body(PeriodSummary(week, totals(), totals())).replace(',', '.'))
        assertEquals("Compared with the previous week: km +13 %, cost +2 %", PeriodSummaryFormatting.versus(PeriodSummary(week, totals(), totals(km = "366.0", fuel = "45.00", expenses = "12.00"))))
    }

    @Test
    fun `the_summary_carries_no_vin_and_no_position`() {
        val texts = listOf(
            PeriodSummaryFormatting.title(week),
            PeriodSummaryFormatting.expanded(PeriodSummary(week, totals(), totals())),
            Texts.PERIOD_SUMMARY_HINT,
        )
        for (text in texts) {
            assertFalse("VIN" in text, text)
            assertFalse("GPS" in text, text)
        }
    }
}
