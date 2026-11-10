package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.PeriodStats

/** The readability fixes of plan C32 (U2-U5, U7): one number format, chart labels that fit, a unit that stays with its number. */
class UiReadabilityTest {
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

    // ---- U4: one format of numbers ----

    @Test
    fun `number groups the thousands with a no-break space and keeps the comma`() {
        assertEquals("999,0", TripsFormatting.number(BigDecimal("999"), 1))
        assertEquals("4\u00A0887,2", TripsFormatting.number(BigDecimal("4887.2"), 1))
        assertEquals("4\u00A0887", TripsFormatting.number(BigDecimal("4886.6"), 0))
        assertEquals("1\u00A0115,0", TripsFormatting.number(BigDecimal("1115"), 1))
        assertEquals("-2\u00A0308,0", TripsFormatting.number(BigDecimal("-2308"), 1))
        assertEquals("1\u00A0000", TripsFormatting.number(BigDecimal("999.5"), 0))
    }

    @Test
    fun `plain stays ungrouped for the csv and the form fields`() {
        assertEquals("4887,2", TripsFormatting.plain(BigDecimal("4887.2"), 1))
    }

    @Test
    fun `a count is grouped too`() {
        assertEquals("592", TripsFormatting.count(592))
        assertEquals("1\u00A0024", TripsFormatting.count(1024))
        assertEquals("trasy: 1\u00A0024", Texts.statsTrips(1024))
        assertEquals("postoje: 1\u00A0183", Texts.parkingStops(1183))
    }

    @Test
    fun `the display formatters group long numbers like the money does`() {
        // The values of the review: 4 887 km, 1 115,0 km, 2 694,8 km were printed without a separator.
        assertEquals("4\u00A0887,2 km", RefuelLogFormatting.distance(BigDecimal("4887.2")))
        assertEquals("1\u00A0263,84 zł", TripsFormatting.money(BigDecimal("1263.84")))
        val stats = PeriodStats(2026, null, 592, BigDecimal("4887.2"), BigDecimal("200.0"), BigDecimal("4.10"), BigDecimal("1263.84"), 0, BigDecimal("25.86"), null, null)
        assertTrue(StatsFormatting.periodLine(stats).contains("4\u00A0887,2 km"), StatsFormatting.periodLine(stats))
        assertTrue(Texts.yearTotals(592, TripsFormatting.number(BigDecimal("4887.2"), 0), "200").contains("4\u00A0887 km"))
        assertEquals("4\u00A0887 km", RefuelFormatting.odometer(4_887.0))
    }

    // ---- U5: the unit stays with its number ----

    @Test
    fun `a tile value keeps its unit on the line of the number`() {
        val tile = keepUnitTogether(TripsFormatting.money(BigDecimal("1263.84")))
        assertEquals("1\u00A0263,84\u00A0zł", tile)
        assertFalse(tile.contains(' '), tile)
        assertEquals("4\u00A0887\u00A0km", keepUnitTogether("4\u00A0887 km"))
        assertEquals("592", keepUnitTogether("592"))
        assertEquals("–", keepUnitTogether("–"))
    }

    // ---- U2/U3: labels of a bar chart ----

    @Test
    fun `a label that fits its bar is printed on every bar`() {
        // 12 month names of three letters (about 17 dp) on 25 dp per bar.
        assertEquals(1, barLabelStride(labelWidthPx = 17f, pitchPx = 25f, gapPx = 4f))
        assertEquals(1, barLabelStride(labelWidthPx = 0f, pitchPx = 25f, gapPx = 4f))
    }

    @Test
    fun `a label wider than its bar is printed on every second or third bar`() {
        // Twelve weekly 24.08 labels (about 30 dp) on 25 dp per bar: every second bar.
        assertEquals(2, barLabelStride(30f, 25f, 4f))
        // A larger system font (130 %) still fits every second one; a much larger one needs every third.
        assertEquals(2, barLabelStride(39f, 25f, 4f))
        assertEquals(3, barLabelStride(60f, 25f, 4f))
        // Room for exactly the label and the gap is still one bar.
        assertEquals(1, barLabelStride(21f, 25f, 4f))
    }

    @Test
    fun `the newest bar always prints its label and the others follow back by the stride`() {
        assertEquals((0..11).toList(), (0..11).filter { barShowsLabel(it, 12, 1) })
        assertEquals(listOf(1, 3, 5, 7, 9, 11), (0..11).filter { barShowsLabel(it, 12, 2) })
        assertEquals(listOf(0, 3, 6), (0..6).filter { barShowsLabel(it, 7, 3) })
        assertTrue(barShowsLabel(11, 12, 5))
        // A stride below one is read as one.
        assertTrue(barShowsLabel(0, 3, 0))
    }

    @Test
    fun `month labels of the charts are short in both languages`() {
        for (language in AppLanguage.entries) {
            Lang.current = language
            for (month in 1..12) {
                val label = ConsumptionFormatting.monthShort(MonthKey(2026, month))
                // January carries the year (`sty 27`), the others are the bare short name.
                assertTrue(label.length <= if (month == 1) 6 else 3, "$language $month: $label")
            }
        }
        Lang.current = AppLanguage.PL
        assertEquals("kwi", ConsumptionFormatting.monthShort(MonthKey(2026, 4)))
        assertEquals("paź", ConsumptionFormatting.monthShort(MonthKey(2026, 10)))
        assertEquals("sty 27", ConsumptionFormatting.monthShort(MonthKey(2027, 1)))
    }

    @Test
    fun `a week label is the whole date day dot month`() {
        val week = EvWeek(LocalDate.of(2026, 8, 24), 3, BigDecimal("10.0"), BigDecimal("6.0"), BigDecimal("60"), null)
        assertEquals("24.08", EvWeeksFormatting.weekLabel(week))
        assertEquals("05.01", EvWeeksFormatting.weekLabel(week.copy(weekStart = LocalDate.of(2026, 1, 5))))
    }

    // ---- F51: CO2 ----

    @Test
    fun `co2 is in kilograms below a tonne and in tonnes from it`() {
        assertEquals("0,0\u00A0kg", TripsFormatting.co2(BigDecimal("0.0")))
        assertEquals("123,4\u00A0kg", TripsFormatting.co2(BigDecimal("123.4")))
        assertEquals("999,9\u00A0kg", TripsFormatting.co2(BigDecimal("999.9")))
        assertEquals("1,00\u00A0t", TripsFormatting.co2(BigDecimal("1000.0")))
        assertEquals("1,23\u00A0t", TripsFormatting.co2(BigDecimal("1234.5")))
        assertEquals("2,05\u00A0t", TripsFormatting.co2(BigDecimal("2050.0")))
        // HALF_UP at the second decimal of the tonnes: 1.2350 t -> 1.24 t.
        assertEquals("1,24\u00A0t", TripsFormatting.co2(BigDecimal("1235.0")))
        assertEquals("12,35\u00A0t", TripsFormatting.co2(BigDecimal("12345.0")))
    }

    @Test
    fun `the co2 unit never wraps away from its number`() {
        for (kg in listOf("0.0", "5.5", "999.9", "1000.0", "98765.4")) {
            assertFalse(TripsFormatting.co2(BigDecimal(kg)).contains(' '), kg)
        }
    }

    @Test
    fun `co2 texts read in both languages with the same unit`() {
        assertEquals("Ślad CO₂: 123,4\u00A0kg", Texts.co2(TripsFormatting.co2(BigDecimal("123.4"))))
        assertEquals("Szacunek ze spalonych litrów (ok. 2,3 kg CO₂ na litr benzyny); bez prądu, produkcji i utylizacji auta.", Texts.co2Hint("2,3"))
        Lang.current = AppLanguage.EN
        assertEquals("CO₂ footprint: 1,23\u00A0t", Texts.co2(TripsFormatting.co2(BigDecimal("1234.5"))))
        assertTrue(Texts.co2Hint("2,3").startsWith("An estimate from the litres burned"))
    }

    // ---- U7: one trend mark ----

    @Test
    fun `the pill text has the direction in words and no arrow`() {
        val rising = trendView(ConsumptionTrend.RISING)
        assertEquals("rosnące (+8,2 %)", ConsumptionFormatting.trendPillText(rising))
        assertEquals("malejące (-6,0 %)", ConsumptionFormatting.trendPillText(trendView(ConsumptionTrend.FALLING, BigDecimal("-6.0"))))
        assertEquals("stabilne (+0,4 %)", ConsumptionFormatting.trendPillText(trendView(ConsumptionTrend.STEADY, BigDecimal("0.4"))))
        // The line of the widget keeps its arrow.
        assertTrue(ConsumptionFormatting.trendLine(rising).orEmpty().startsWith("Trend: \u2197 rosnące"))
        Lang.current = AppLanguage.EN
        assertEquals("rising (+8,2 %)", ConsumptionFormatting.trendPillText(rising))
        assertNotNull(ConsumptionFormatting.trendLine(rising))
    }

    @Test
    fun `no trend gives no pill text`() {
        assertNull(ConsumptionFormatting.trendPillText(trendView(null)))
    }

    private fun trendView(trend: ConsumptionTrend?, change: BigDecimal = BigDecimal("8.2")) = ConsumptionView(
        overall = BigDecimal("4.50"),
        overallKm = BigDecimal("1000.0"),
        months = emptyList(),
        trend = trend,
        changePct = if (trend == null) null else change,
        monthCosts = emptyList(),
        totalCost = null,
        totalTripsWithoutCost = 0,
    )
}
