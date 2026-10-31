package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/** Byte order mark: without it Excel on Windows reads UTF-8 as cp1250 and breaks Polish letters. */
const val CSV_BOM: String = "\uFEFF"

/** RFC 4180 line end. */
private const val CSV_LINE_END = "\r\n"

/** Polish spreadsheets use the comma for decimals, so the separator is the semicolon. */
private const val CSV_SEPARATOR = ";"

/**
 * The trips of the selected vehicle as a spreadsheet (PRD F6, docs/SPEC.md §14.4):
 * the header of [Texts.CSV_HEADER], then one row per trip, oldest first (ties by id),
 * lines ended by CRLF. Cells hold plain numbers (unit in the header), rounded HALF_UP
 * like the trips list and written without `Locale`; times are local to [zone], the
 * zone of the list. Price, cost and source stay empty exactly when the list says
 * "brak ceny" ([hasNoCost]). No GPS, no VIN, no trip id, no totals. The [CSV_BOM] is
 * added by the caller when it turns the text into bytes.
 */
fun tripsCsv(trips: List<StoredTrip>, zone: ZoneId): String {
    val out = StringBuilder()
    out.append(csvRow(Texts.CSV_HEADER))
    val ordered = trips.sortedWith(compareBy({ it.record.trip.start }, { it.record.trip.id }))
    for (stored in ordered) {
        val trip = stored.record.trip
        val cost = stored.cost
        val priced = !hasNoCost(cost)
        out.append(
            csvRow(
                listOf(
                    csvDateTime(trip.start, zone),
                    csvDateTime(trip.end, zone),
                    TripsFormatting.plain(toBigDecimal(trip.distanceKm), 1),
                    TripsFormatting.plain(trip.fuelL, 2),
                    trip.evDistanceKm?.let { TripsFormatting.plain(toBigDecimal(it), 1) } ?: "",
                    // The full precision of the domain; the list shows two places (docs/SPEC.md §14.4).
                    cost?.pricePerLiter?.takeIf { priced }?.let { TripsFormatting.plain(it, 3) } ?: "",
                    cost?.costPln?.takeIf { priced }?.let { TripsFormatting.plain(it, 2) } ?: "",
                    if (!priced) "" else sourceLabel(requireNotNull(cost).source),
                ),
            ),
        )
    }
    return out.toString()
}

/** Suggested file name, e.g. `toyota-companion-trasy-2026-10-31.csv`; no VIN (docs/SECURITY.md). */
fun tripsCsvFileName(day: LocalDate): String = "toyota-companion-trasy-$day.csv"

/** `2026-10-29 14:05` in [zone] — sortable in a spreadsheet; no `String.format` (see [TripsFormatting]). */
fun csvDateTime(at: Instant, zone: ZoneId): String {
    val local = at.atZone(zone)
    return local.year.toString() + "-" + pad(local.monthValue) + "-" + pad(local.dayOfMonth) + " " +
        pad(local.hour) + ":" + pad(local.minute)
}

/** A number as [TripsFormatting.plain] writes it; such a cell is never a formula. */
private val CSV_NUMBER = Regex("-?[0-9]+(,[0-9]+)?")

/**
 * One cell: quoted when it holds the separator, a quote or a line break (quotes doubled,
 * RFC 4180). Text (not a number) starting with `=`, `+`, `-` or `@` gets a leading `'`,
 * so a spreadsheet never runs it as a formula (CSV injection, docs/SPEC.md §14.4).
 */
fun csvCell(text: String): String {
    val formulaLike = text.isNotEmpty() && text[0] in "=+-@" && !CSV_NUMBER.matches(text)
    val guarded = if (formulaLike) "'$text" else text
    val needsQuotes = guarded.any { it == ';' || it == '"' || it == '\r' || it == '\n' }
    return if (needsQuotes) "\"" + guarded.replace("\"", "\"\"") + "\"" else guarded
}

private fun csvRow(cells: List<String>): String = cells.joinToString(CSV_SEPARATOR) { csvCell(it) } + CSV_LINE_END

private fun sourceLabel(source: CostSource): String = when (source) {
    CostSource.REFUEL -> Texts.CSV_SOURCE_REFUEL
    CostSource.DEFAULT -> Texts.CSV_SOURCE_DEFAULT
    CostSource.NONE -> ""
}

private fun pad(value: Int): String = value.toString().padStart(2, '0')
