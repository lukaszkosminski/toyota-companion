package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.ServiceRecord
import pl.lk.toyotacompanion.core.domain.ToyotaNotification

/**
 * Formatting of the service history and the notification list (docs/SPEC.md §45-§46).
 *
 * No `Locale.getDefault()`: the date is built from its parts and the mileage goes through
 * [TripsFormatting.odometer], so the same values show on every device. Nothing here guesses: a missing
 * part is "brak danych", and only the owner-confirmed category codes get a description.
 */

/** One service record as the screen shows it: a title line and the details Toyota actually sent. */
data class ServiceRow(
    val title: String,
    val mileage: String?,
    val category: String?,
    val dealer: String,
    val operations: List<String>?,
)

/**
 * The rows under the date of a record, each as label and value: "Przebieg", "Kategoria", "Dealer" and "Czynności". Mileage and
 * category are left out when Toyota sent none; the dealer says "brak danych" and the operations say that no description came. The category
 * is shown with the owner-confirmed meaning and the code in parentheses for `MNT` and `VDE`; every other code stays as sent.
 */
fun serviceDetails(row: ServiceRow): List<LabelValue> = listOfNotNull(
    row.mileage?.let { LabelValue(Texts.SERVICE_MILEAGE_LABEL, it) },
    row.category?.let { LabelValue(Texts.SERVICE_CATEGORY_LABEL, serviceCategoryText(it)) },
    LabelValue(Texts.SERVICE_DEALER, row.dealer),
    LabelValue(Texts.SERVICE_OPERATIONS_LABEL, row.operations?.joinToString("\n") ?: Texts.SERVICE_NO_OPERATIONS_SHORT),
)

/**
 * Toyota's category code with the meaning the owner confirmed on 2026-11-10: "Przegląd okresowy (MNT)" and
 * "Wydanie auta (VDE)". Every other code is returned unchanged - the app still does not guess.
 */
fun serviceCategoryText(code: String): String = when (code) {
    "MNT" -> "${Texts.SERVICE_CATEGORY_MAINTENANCE} ($code)"
    "VDE" -> "${Texts.SERVICE_CATEGORY_DELIVERY} ($code)"
    else -> code
}

/** `12.03.2026`, or "brak daty" when Toyota sent none. */
fun serviceDateText(date: LocalDate?): String = if (date == null) {
    Texts.SERVICE_NO_DATE
} else {
    val day = if (date.dayOfMonth < 10) "0${date.dayOfMonth}" else date.dayOfMonth.toString()
    val month = if (date.monthValue < 10) "0${date.monthValue}" else date.monthValue.toString()
    "$day.$month.${date.year}"
}

/** The dealer under one label: `servicingDealer`, else `serviceProvider`, else "brak danych". */
fun serviceDealerText(record: ServiceRecord): String = record.dealer ?: Texts.COMPONENT_UNKNOWN

fun serviceRow(record: ServiceRecord): ServiceRow = ServiceRow(
    title = serviceDateText(record.date),
    mileage = record.mileage?.let { Texts.serviceMileage(it, record.mileageUnit) },
    category = record.category,
    dealer = serviceDealerText(record),
    operations = record.operations,
)

/** `01.11.2026 10:00` in the given zone, or "brak daty" when Toyota sent none. */
fun notificationDateText(date: Instant?, zone: ZoneId): String =
    if (date == null) Texts.NOTIFICATIONS_NO_DATE else TripsFormatting.startDateTime(date, zone)

/** "przeczytane" / "nieprzeczytane"; null when Toyota did not say. */
fun notificationReadText(isRead: Boolean?): String? = isRead?.let {
    if (it) Texts.NOTIFICATIONS_READ else Texts.NOTIFICATIONS_UNREAD
}

/**
 * The category and the type of a notification on one line; null when Toyota sent neither. Only the values the reference and the
 * fixtures name are translated (see [notificationCategoryText]); any other value is shown as Toyota sent it.
 */
fun notificationKindText(notification: ToyotaNotification): String? {
    val parts = listOfNotNull(notification.category?.let(::notificationCategoryText), notification.type?.let(::notificationTypeText))
    return parts.takeIf { it.isNotEmpty() }?.joinToString(Texts.SEPARATOR)
}

/**
 * Polish name of Toyota's `displayCategory` for the values the reference documents (`General`, `Vehicle Alerts`, `Remote Commands`);
 * English keeps the value, and an unknown value is returned unchanged.
 */
fun notificationCategoryText(category: String): String = when (category.trim()) {
    "General" -> Lang.pick("Ogólne", "General")
    "Vehicle Alerts" -> Lang.pick("Alerty pojazdu", "Vehicle Alerts")
    "Remote Commands" -> Lang.pick("Zdalne polecenia", "Remote Commands")
    else -> category
}

/** Polish name of Toyota's notification `type` for the value the fixtures carry (`alert`); any other value is returned unchanged. */
fun notificationTypeText(type: String): String = when (type.trim()) {
    "alert" -> Lang.pick("ostrzeżenie", "alert")
    else -> type
}

/** The masked VIN that Toyota puts in front of some messages: stars, the last four characters of the VIN, then `: `. */
private val MASKED_VIN_PREFIX = Regex("^\\*{4,}[A-Za-z0-9]{4}: ")

/**
 * The text of a notification without its masked VIN prefix (`*************4408: Klimatyzacja …` shows `Klimatyzacja …`). Only the exact
 * shape is cut; stars or a VIN further inside the text stay as they are (docs/SPEC.md §46). `null` when nothing is left.
 */
fun notificationMessageText(message: String?): String? =
    message?.let { MASKED_VIN_PREFIX.replaceFirst(it, "") }?.trim()?.takeIf { it.isNotEmpty() }
