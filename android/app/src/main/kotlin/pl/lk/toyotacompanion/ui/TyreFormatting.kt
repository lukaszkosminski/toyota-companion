package pl.lk.toyotacompanion.ui

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import pl.lk.toyotacompanion.core.domain.TyreDue
import pl.lk.toyotacompanion.core.domain.TyreReminder
import pl.lk.toyotacompanion.core.domain.TyreSet
import pl.lk.toyotacompanion.core.domain.TyreSetUsage

/**
 * The text of the tyre card and of its reminder (PRD F56, docs/SPEC.md §56). Pure and independent of `Locale`: dates are built from their parts,
 * numbers go through [TripsFormatting.number], months are declined by [plPlural]. Only dates, kilometres and months: no VIN, no position.
 */
object TyreFormatting {
    /** `15.04.2026`, the same shape as the rest of the app. */
    fun day(date: LocalDate): String =
        date.dayOfMonth.toString().padStart(2, '0') + "." + date.monthValue.toString().padStart(2, '0') + "." + date.year

    /** `3 mies.` / `1 miesiąc` / `12 miesięcy`, with the number grouped like the rest of the app. */
    fun months(count: Int): String = Texts.tyreMonths(count)

    /** Full months from the fitting to [today]; never negative. */
    fun monthsSince(usage: TyreSetUsage, today: LocalDate): Int =
        ChronoUnit.MONTHS.between(usage.change.date, today).toInt().coerceAtLeast(0)

    /** One line of the card for a fitting: `Letni · od 15.04.2026 · 2 300 km · 7 miesięcy` or the closed range with `… – 20.10.2026`. */
    fun changeLine(usage: TyreSetUsage, today: LocalDate): String {
        val name = Texts.tyreSetName(usage.change.set)
        val span = usage.until?.let { "${day(usage.change.date)}–${day(it)}" } ?: "od ${day(usage.change.date)}"
        val parts = listOfNotNull(
            name,
            span,
            TripsFormatting.number(usage.km, 0) + " km",
            if (usage.until == null) months(monthsSince(usage, today)) else null,
        )
        return parts.joinToString(Texts.SEPARATOR)
    }

    /** `Trasy sprzed pierwszej zmiany: 12 km` (not counted for any set). */
    fun beforeFirst(km: java.math.BigDecimal, trips: Int): String = Texts.tyreBeforeFirst(trips, TripsFormatting.number(km, 0))

    /** The reminder as one line for the card and the notification: `Letnie: 6 miesięcy od założenia` or `Letnie: 15 000 km od założenia`. */
    fun reminderLine(reminder: TyreReminder): String {
        val name = Texts.tyreSetName(reminder.usage.change.set)
        return when (reminder.due) {
            TyreDue.MONTHS -> Texts.tyreReminderMonths(name, months(reminder.months))
            TyreDue.KM -> Texts.tyreReminderKm(name, TripsFormatting.number(reminder.km, 0) + " km")
        }
    }
}
