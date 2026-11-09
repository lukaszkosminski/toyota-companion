package pl.lk.toyotacompanion.ui

import java.time.LocalDate

/**
 * The non-fuel expenses as a spreadsheet (PRD F47, docs/SPEC.md §48), in the same format as the trips sheet
 * (§14.4): the header of [Texts.CSV_EXPENSES_HEADER], one row per expense, oldest first (ties by id), `;`
 * separator, comma decimals, CRLF, no `Locale`. The category is the name the screen shows (PL/EN of the UI);
 * the note is escaped by [csvCell], so a formula-like text is never executed. No id, VIN or GPS. The
 * [CSV_BOM] is added by the caller when it turns the text into bytes.
 */
fun expensesCsv(expenses: List<Expense>): String {
    val out = StringBuilder()
    out.append(csvRow(Texts.CSV_EXPENSES_HEADER))
    val ordered = expenses.sortedWith(compareBy({ it.date }, { it.id }))
    for (expense in ordered) {
        out.append(
            csvRow(
                listOf(
                    csvDate(expense.date),
                    Texts.expenseCategoryName(expense.category),
                    TripsFormatting.plain(expense.amount, 2),
                    expense.note ?: "",
                ),
            ),
        )
    }
    return out.toString()
}

/** Suggested file name, e.g. `toyota-companion-wydatki-2026-10-31.csv`; no VIN (docs/SECURITY.md). */
fun expensesCsvFileName(day: LocalDate): String = "toyota-companion-wydatki-$day.csv"
