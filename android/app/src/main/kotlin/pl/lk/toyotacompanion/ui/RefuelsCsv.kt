package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/**
 * The confirmed refuels of the selected vehicle as a spreadsheet (PRD F47, docs/SPEC.md §48), in the same
 * format as the trips sheet (§14.4): the header of [Texts.CSV_REFUELS_HEADER], one row per refuel, oldest
 * first (ties by id), `;` separator, comma decimals, CRLF, no `Locale`. Numbers are plain (unit in the
 * header); a missing value stays empty, never 0. No VIN, GPS or id. The [CSV_BOM] is added by the caller
 * when it turns the text into bytes.
 */
fun refuelsCsv(refuels: List<Refuel>, zone: ZoneId): String {
    val out = StringBuilder()
    out.append(csvRow(Texts.CSV_REFUELS_HEADER))
    val ordered = refuels.sortedWith(compareBy({ it.ts }, { it.id }))
    for (refuel in ordered) {
        out.append(
            csvRow(
                listOf(
                    csvDateTime(refuel.ts, zone),
                    TripsFormatting.plain(toBigDecimal(refuel.odometerKm), 1),
                    TripsFormatting.plain(BigDecimal(refuel.levelBefore), 0),
                    TripsFormatting.plain(BigDecimal(refuel.levelAfter), 0),
                    // The full precision of the domain; the log shows it without trailing zeros.
                    refuel.liters?.let { TripsFormatting.plain(it, 3) } ?: "",
                    refuel.pricePerLiter?.let { TripsFormatting.plain(it, 3) } ?: "",
                    refuel.totalCost?.let { TripsFormatting.plain(it, 2) } ?: "",
                    refuel.litersSource?.let { litersSourceLabel(it) } ?: "",
                    refuel.filledToFull?.let { if (it) Texts.CSV_YES else Texts.CSV_NO } ?: "",
                ),
            ),
        )
    }
    return out.toString()
}

/** Suggested file name, e.g. `toyota-companion-tankowania-2026-10-31.csv`; no VIN (docs/SECURITY.md). */
fun refuelsCsvFileName(day: LocalDate): String = "toyota-companion-tankowania-$day.csv"

private fun litersSourceLabel(source: LitersSource): String = when (source) {
    LitersSource.RECEIPT -> Texts.LOG_SOURCE_RECEIPT
    LitersSource.DERIVED -> Texts.LOG_SOURCE_DERIVED
    LitersSource.ESTIMATE -> Texts.LOG_SOURCE_ESTIMATE
}
