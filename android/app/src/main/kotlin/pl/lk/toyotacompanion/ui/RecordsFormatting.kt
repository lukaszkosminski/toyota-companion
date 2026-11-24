package pl.lk.toyotacompanion.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.lk.toyotacompanion.core.domain.Record

/** Formats one personal record (PRD F62, docs/SPEC.md §62) for the records screen. */
object RecordsFormatting {
    private val PL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("pl"))
    private val EN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

    fun dateOf(record: Record): String {
        val date = record.date ?: return ""
        return date.format(if (Lang.isEnglish) EN_DATE else PL_DATE)
    }

    /** The big value with its unit: `312,4 km`, `4,50 l/100 km`, `80,0 %`. */
    fun valueOf(record: Record): String {
        val formatted = record.value.toPlainString().replace('.', ',')
        return if (record.unit == "%") "$formatted %" else "$formatted ${record.unit}"
    }

    /** The sub-line under the value: the date plus the context, e.g. `12 czerwca 2026 · 312,4 km`. */
    fun subLine(record: Record): String {
        val date = dateOf(record)
        val context = record.context
        return when {
            context != null && date.isNotEmpty() -> "$date · $context"
            context != null -> context
            else -> date
        }
    }
}
