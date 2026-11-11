package pl.lk.toyotacompanion.ui

import java.time.LocalDate

/**
 * The automatic archive copy (PRD F60, docs/SPEC.md §60): one dated JSON file per day in a folder the owner picked, the newest
 * [AUTO_COPY_KEEP] kept. Pure, so the naming, the daily rule and the rotation are tested without Android.
 */

/** The name prefix of every copy; only names with it are ever rotated away. */
const val AUTO_COPY_PREFIX: String = "toyota-companion-"

/** How many daily copies stay in the folder. */
const val AUTO_COPY_KEEP: Int = 10

/** The name of the copy for [date]: `toyota-companion-2026-11-11.json`, the same day format as the manual export. */
fun autoCopyName(date: LocalDate): String = AUTO_COPY_PREFIX + dayText(date) + ".json"

/** The day of a copy name; `null` for any other name, so a foreign file is never touched. */
fun autoCopyDate(name: String): LocalDate? {
    if (!name.startsWith(AUTO_COPY_PREFIX) || !name.endsWith(".json")) return null
    val day = name.removePrefix(AUTO_COPY_PREFIX).removeSuffix(".json")
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(day)) return null
    return runCatching { LocalDate.parse(day) }.getOrNull()
}

/** Whether today's copy still has to be written: a folder is set and nothing was copied today yet. */
fun shouldCopyToday(hasFolder: Boolean, lastCopy: LocalDate?, today: LocalDate): Boolean =
    hasFolder && lastCopy != today

/**
 * The copies to delete from [existing] so that only the newest [keep] stay: names of this app, newest day first, the rest left out.
 * Anything that is not a copy of this app is never returned.
 */
fun copiesToDelete(existing: List<String>, keep: Int = AUTO_COPY_KEEP, justWritten: String? = null): List<String> {
    // The copy written a moment ago always stays and takes one of the places: with ten files dated in the future (a clock that was
    // moved back) it would otherwise be the oldest one and be deleted right after it was written.
    val ours = existing.filter { it != justWritten }.mapNotNull { name -> autoCopyDate(name)?.let { it to name } }
        .sortedByDescending { it.first }
    val places = (if (justWritten != null && justWritten in existing) keep - 1 else keep).coerceAtLeast(0)
    return ours.drop(places).map { it.second }
}

/** `2026-11-11`, the day part of a copy name; no `Locale` (docs/SPEC.md §14.3). */
private fun dayText(date: LocalDate): String = date.year.toString() + "-" +
    date.monthValue.toString().padStart(2, '0') + "-" + date.dayOfMonth.toString().padStart(2, '0')
