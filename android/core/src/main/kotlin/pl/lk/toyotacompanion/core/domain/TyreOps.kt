package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Two sets of tyres, changed by hand (PRD F56, docs/SPEC.md §56). Mirrors `backend/app/domain/tyres.py` (the same test names live in
 * `backend/tests/test_domain_tyres.py`). Pure: the settings give text, the archive gives trips; this file parses, assigns and decides.
 */

/** Summer or winter set; [code] is the letter kept in the settings text. */
enum class TyreSet(val code: Char) { SUMMER('S'), WINTER('W') }

/** One fitting of a set: the day it went on, and the odometer that day when the owner typed it. */
data class TyreChange(val date: LocalDate, val set: TyreSet, val startOdometerKm: Int?)

/** The reminder thresholds of one set: full months since fitting and km of the archive since fitting; `null` = no reminder of that kind. */
data class TyreThreshold(val months: Int?, val km: Int?)

/** Trips of one fitting: from its day up to the next fitting ([until]), `null` for the set on now. */
data class TyreSetUsage(val change: TyreChange, val until: LocalDate?, val trips: Int, val km: BigDecimal)

/** All fittings with their usage, and the trips that are older than the first fitting (no set, counted apart). */
data class TyreUsage(val sets: List<TyreSetUsage>, val beforeFirstTrips: Int, val beforeFirstKm: BigDecimal)

enum class TyreDue { MONTHS, KM }

/** A reminder for the set on now: [months] full months since fitting and [km] of the archive since fitting. */
data class TyreReminder(val usage: TyreSetUsage, val due: TyreDue, val months: Int, val km: BigDecimal)

/** Checks for a new fitting: a date in the future, the same day twice, or before the last fitting. */
sealed interface TyreChangeCheck {
    data object Ok : TyreChangeCheck

    data object Future : TyreChangeCheck

    data object Duplicate : TyreChangeCheck

    data object BeforeLast : TyreChangeCheck
}

/** Largest odometer and km threshold the owner can type (km), and the largest month threshold. */
const val TYRE_MAX_ODOMETER_KM: Int = 2_000_000
const val TYRE_MAX_KM: Int = 300_000
const val TYRE_MAX_MONTHS: Int = 120

private val ZERO = BigDecimal.ZERO

/**
 * The fittings from the settings text `2026-04-15|S|1234;2025-10-20|W|` (date, set letter, odometer or empty). A broken entry is skipped, a second
 * fitting of the same day is dropped, and the result is oldest first. Garbage or `null` is no fitting at all.
 */
fun parseTyreChanges(text: String?): List<TyreChange> {
    if (text.isNullOrBlank()) return emptyList()
    val parsed = text.split(';').mapNotNull { entry ->
        val parts = entry.split('|')
        if (parts.size != 3) return@mapNotNull null
        val date = runCatching { LocalDate.parse(parts[0].trim()) }.getOrNull() ?: return@mapNotNull null
        val set = TyreSet.entries.firstOrNull { it.code.toString() == parts[1].trim() } ?: return@mapNotNull null
        val odometerText = parts[2].trim()
        val odometer = if (odometerText.isEmpty()) null else odometerText.toIntOrNull()?.takeIf { it in 0..TYRE_MAX_ODOMETER_KM }
        if (odometerText.isNotEmpty() && odometer == null) return@mapNotNull null
        TyreChange(date, set, odometer)
    }
    return parsed.sortedBy { it.date }.distinctBy { it.date }
}

/** The settings text of the fittings, oldest first; [parseTyreChanges] reads it back unchanged. */
fun formatTyreChanges(changes: List<TyreChange>): String =
    changes.sortedBy { it.date }.joinToString(";") { "${it.date}|${it.set.code}|${it.startOdometerKm ?: ""}" }

/**
 * The thresholds from `S:6:15000;W::` (set letter, months, km; empty = none). Missing or out-of-range values are `null`; a set that is not in the
 * text has no threshold at all.
 */
fun parseTyreThresholds(text: String?): Map<TyreSet, TyreThreshold> {
    val result = TyreSet.entries.associateWith { TyreThreshold(null, null) }.toMutableMap()
    if (text.isNullOrBlank()) return result
    for (entry in text.split(';')) {
        val parts = entry.split(':')
        if (parts.size != 3) continue
        val set = TyreSet.entries.firstOrNull { it.code.toString() == parts[0].trim() } ?: continue
        val months = parts[1].trim().toIntOrNull()?.takeIf { it in 1..TYRE_MAX_MONTHS }
        val km = parts[2].trim().toIntOrNull()?.takeIf { it in 1..TYRE_MAX_KM }
        result[set] = TyreThreshold(months, km)
    }
    return result
}

/** The settings text of the thresholds; [parseTyreThresholds] reads it back. */
fun formatTyreThresholds(thresholds: Map<TyreSet, TyreThreshold>): String =
    TyreSet.entries.joinToString(";") { set ->
        val t = thresholds[set] ?: TyreThreshold(null, null)
        "${set.code}:${t.months ?: ""}:${t.km ?: ""}"
    }

/** The reminder keys already sent, from a comma-separated text; garbage is no key. */
fun parseTyreReminderKeys(text: String?): Set<String> =
    text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

fun formatTyreReminderKeys(keys: Set<String>): String = keys.sorted().joinToString(",")

/**
 * The trips of each fitting by the local day of their start in [zone]: a fitting owns the days from its own day up to the next fitting, the
 * set on now runs to today. A trip with no positive distance is no part of the usage (as in F10). Trips before the first fitting are counted apart.
 */
fun tyreUsage(changes: List<TyreChange>, trips: List<Trip>, zone: ZoneId): TyreUsage {
    val ordered = changes.sortedBy { it.date }
    val days = trips
        .filter { it.distanceKm.isFinite() && it.distanceKm > 0.0 }
        .map { it.start.atZone(zone).toLocalDate() to toBigDecimal(it.distanceKm) }
    val sets = ordered.mapIndexed { index, change ->
        val until = ordered.getOrNull(index + 1)?.date
        val owned = days.filter { (day, _) -> !day.isBefore(change.date) && (until == null || day.isBefore(until)) }
        TyreSetUsage(change, until, owned.size, owned.fold(ZERO) { sum, (_, km) -> sum.add(km) }.setScale(1, RoundingMode.HALF_UP))
    }
    val first = ordered.firstOrNull()?.date
    val before = days.filter { first == null || it.first.isBefore(first) }
    return TyreUsage(sets, before.size, before.fold(ZERO) { sum, (_, km) -> sum.add(km) }.setScale(1, RoundingMode.HALF_UP))
}

/**
 * The reminders due today (PRD F56). Only the set on now counts, from its own day: [TyreThreshold.months] full months since fitting, or
 * [TyreThreshold.km] km of the archive since fitting. No threshold, no reminder; a fitting in the future is not due yet.
 */
fun dueTyreReminders(usage: TyreUsage, thresholds: Map<TyreSet, TyreThreshold>, today: LocalDate): List<TyreReminder> {
    val current = usage.sets.lastOrNull()?.takeIf { it.until == null } ?: return emptyList()
    if (current.change.date.isAfter(today)) return emptyList()
    val threshold = thresholds[current.change.set] ?: return emptyList()
    val months = ChronoUnit.MONTHS.between(current.change.date, today).toInt()
    val due = mutableListOf<TyreReminder>()
    threshold.months?.let { if (months >= it) due += TyreReminder(current, TyreDue.MONTHS, months, current.km) }
    threshold.km?.let { if (current.km >= BigDecimal(it)) due += TyreReminder(current, TyreDue.KM, months, current.km) }
    return due
}

/** The key of a reminder: one per set, per fitting day and per kind (`SUMMER:2026-04-15:MONTHS`). A changed threshold does not repeat it. */
fun tyreReminderKey(reminder: TyreReminder): String =
    "${reminder.usage.change.set.name}:${reminder.usage.change.date}:${reminder.due.name}"

/** Whether a new fitting on [date] fits after [existing] and is not in the future. */
fun checkTyreChange(existing: List<TyreChange>, date: LocalDate, today: LocalDate): TyreChangeCheck = when {
    date.isAfter(today) -> TyreChangeCheck.Future
    existing.any { it.date == date } -> TyreChangeCheck.Duplicate
    existing.any { it.date.isAfter(date) } -> TyreChangeCheck.BeforeLast
    else -> TyreChangeCheck.Ok
}

/** A typed whole number for the forms: blank is `null` (no value), wrong or above [max] is `null` too; the caller tells blank from wrong. */
fun parseTyreNumber(text: String, max: Int): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..max }

/** The fittings without the newest one (a mistaken entry is removed the last-in, first-out way); an empty list stays empty. */
fun withoutLastTyreChange(changes: List<TyreChange>): List<TyreChange> = changes.sortedBy { it.date }.dropLast(1)

/**
 * The reminder keys without those of [change]: a key starts with its set and fitting day, so a removed fitting forgets its own reminders and a
 * fitting entered again on the same day can be reminded again. Other keys stay.
 */
fun tyreKeysWithoutChange(keys: Set<String>, change: TyreChange): Set<String> {
    val prefix = "${change.set.name}:${change.date}:"
    return keys.filterNot { it.startsWith(prefix) }.toSet()
}
