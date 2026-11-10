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
 * [TripsFormatting.odometer], so the same values show on every device. Nothing here interprets what
 * Toyota sent — a missing part is "brak danych", never a guess.
 */

/** One service record as the screen shows it: a title line and the details Toyota actually sent. */
data class ServiceRow(
    val title: String,
    val mileage: String?,
    val category: String?,
    val dealer: String,
    val operations: List<String>?,
)

/** One label-value row of a service record (docs/SPEC.md §45). */
data class ServiceDetail(val label: String, val value: String)

/**
 * The rows under the date of a record, each as label and value: "Przebieg", "Kategoria (kod Toyoty)", "Dealer" and "Czynności". Mileage and
 * category are left out when Toyota sent none; the dealer says "brak danych" and the operations say that no description came. The category
 * is shown as Toyota's code (`MNT`, `VDE`): the meaning of the codes is not documented, so none is guessed.
 */
fun serviceDetails(row: ServiceRow): List<ServiceDetail> = listOfNotNull(
    row.mileage?.let { ServiceDetail(Texts.SERVICE_MILEAGE_LABEL, it) },
    row.category?.let { ServiceDetail(Texts.SERVICE_CATEGORY_LABEL, it) },
    ServiceDetail(Texts.SERVICE_DEALER, row.dealer),
    ServiceDetail(Texts.SERVICE_OPERATIONS_LABEL, row.operations?.joinToString("\n") ?: Texts.SERVICE_NO_OPERATIONS_SHORT),
)

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

/** The category and the type of a notification on one line; null when Toyota sent neither. */
fun notificationKindText(notification: ToyotaNotification): String? {
    val parts = listOfNotNull(notification.category, notification.type)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(Texts.SEPARATOR)
}
