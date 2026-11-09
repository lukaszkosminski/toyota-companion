package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.CostSource

/**
 * The spreadsheet of the non-fuel expenses (PRD F47, docs/SPEC.md §48): the §14.4 format, the category
 * names of the screen, the amount with two places, and a note that is escaped and never runs as a formula.
 * The byte order mark belongs to the caller that writes bytes, so this text has none.
 */
private const val CRLF = "\r\n"

private fun expense(
    id: String,
    date: LocalDate = LocalDate.of(2026, 10, 29),
    category: ExpenseCategory = ExpenseCategory.SERVICE,
    amount: String = "250.50",
    note: String? = "olej",
): Expense = Expense(id, date, category, BigDecimal(amount), note)

private fun headerLine(): String = Texts.CSV_EXPENSES_HEADER.joinToString(";")

class ExpensesCsvTest {
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

    @Test
    fun `the file starts with the header from the texts and holds no byte order mark`() {
        val text = expensesCsv(listOf(expense("e1")))

        assertEquals(4, Texts.CSV_EXPENSES_HEADER.size)
        assertEquals(headerLine(), text.split(CRLF)[0])
        assertFalse(text.startsWith(CSV_BOM), "the BOM is added when the text becomes bytes")
        assertTrue(text.endsWith(CRLF), "RFC 4180 line end")
    }

    @Test
    fun `an empty list writes only the header`() {
        assertEquals(headerLine() + CRLF, expensesCsv(emptyList()))
    }

    @Test
    fun `one row has the date, the category name and the amount with two places`() {
        val row = expensesCsv(listOf(expense("e1"))).split(CRLF)[1]

        assertEquals("2026-10-29;Serwis;250,50;olej", row)
    }

    @Test
    fun `the amount is rounded HALF_UP and grouped nowhere`() {
        val row = expensesCsv(listOf(expense("e1", amount = "1.005"))).split(CRLF)[1]

        assertEquals("2026-10-29;Serwis;1,01;olej", row)
    }

    @Test
    fun `rows are oldest first and ties go by id`() {
        val b = expense("b", date = LocalDate.of(2026, 10, 28), amount = "2.00")
        val a = expense("a", date = LocalDate.of(2026, 10, 28), amount = "1.00")
        val newest = expense("c", date = LocalDate.of(2026, 10, 29), amount = "3.00")
        val rows = expensesCsv(listOf(newest, b, a)).split(CRLF).filter { it.isNotEmpty() }.drop(1)

        assertEquals(3, rows.size)
        assertTrue(rows[0].startsWith("2026-10-28;Serwis;1,00;"))
        assertTrue(rows[1].startsWith("2026-10-28;Serwis;2,00;"))
        assertTrue(rows[2].startsWith("2026-10-29;Serwis;3,00;"))
    }

    @Test
    fun `a missing note is an empty cell`() {
        val row = expensesCsv(listOf(expense("e1", note = null))).split(CRLF)[1]

        assertEquals("2026-10-29;Serwis;250,50;", row)
    }

    @Test
    fun `a note is escaped and never runs as a formula`() {
        val rows = expensesCsv(
            listOf(
                expense("a", note = "=SUM(A1:A2)"),
                expense("b", note = " =1+1"),
                expense("c", note = "\t=1+1"),
                expense("d", note = "@x"),
                expense("e", note = "+1"),
                expense("f", note = "-1"),
                expense("g", note = "olej; filtr \"kabinowy\""),
            ),
        ).split(CRLF).filter { it.isNotEmpty() }.drop(1)

        assertEquals("2026-10-29;Serwis;250,50;'=SUM(A1:A2)", rows[0])
        assertEquals("2026-10-29;Serwis;250,50;\"' =1+1\"", rows[1])
        assertEquals("2026-10-29;Serwis;250,50;\"'\t=1+1\"", rows[2])
        assertEquals("2026-10-29;Serwis;250,50;'@x", rows[3])
        assertEquals("2026-10-29;Serwis;250,50;'+1", rows[4])
        assertEquals("2026-10-29;Serwis;250,50;-1", rows[5], "a negative number is not a formula")
        assertEquals("2026-10-29;Serwis;250,50;\"olej; filtr \"\"kabinowy\"\"\"", rows[6])
    }

    @Test
    fun `a note with a line break is quoted and keeps it`() {
        val text = expensesCsv(listOf(expense("g", note = "linia1\r\nlinia2")))

        assertTrue(text.contains("\"linia1\r\nlinia2\""), text)
    }

    @Test
    fun `the categories follow the chosen language`() {
        val polish = expensesCsv(listOf(expense("e1", category = ExpenseCategory.TYRES)))
        assertEquals("2026-10-29;Opony;250,50;olej", polish.split(CRLF)[1])

        Lang.current = AppLanguage.EN
        try {
            val english = expensesCsv(listOf(expense("e1", category = ExpenseCategory.TYRES)))
            assertEquals("2026-10-29;Tyres;250,50;olej", english.split(CRLF)[1])
            assertEquals("Date;Category;Amount [zł];Note", english.split(CRLF)[0])
        } finally {
            Lang.current = AppLanguage.PL
        }
    }

    @Test
    fun `the text does not depend on the default locale`() {
        val rows = listOf(expense("e1", amount = "1234.50"))
        val expected = expensesCsv(rows)
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("pl-PL"))) {
            val previous = Locale.getDefault()
            try {
                Locale.setDefault(locale)
                assertEquals(expected, expensesCsv(rows), locale.toString())
            } finally {
                Locale.setDefault(previous)
            }
        }
    }

    @Test
    fun `the content carries no vin and no expense id`() {
        val text = expensesCsv(listOf(expense("expense-secret-id", category = ExpenseCategory.OTHER)))

        assertTrue("VIN1" !in text)
        assertTrue("expense-secret-id" !in text)
        // A cost source is a trip concept; nothing here should mention it.
        assertTrue(CostSource.REFUEL.name !in text)
    }

    @Test
    fun `the file name says what it holds and carries no vin`() {
        assertEquals("toyota-companion-wydatki-2026-10-31.csv", expensesCsvFileName(LocalDate.of(2026, 10, 31)))
        assertEquals("toyota-companion-wydatki-2026-01-05.csv", expensesCsvFileName(LocalDate.of(2026, 1, 5)))
    }
}
