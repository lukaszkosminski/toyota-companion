package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import pl.lk.toyotacompanion.core.domain.PeriodStats
import pl.lk.toyotacompanion.core.domain.Season
import pl.lk.toyotacompanion.core.domain.TripShare

/**
 * Polish text of the statistics screen (docs/SPEC.md §16-§17). Pure and independent of
 * `Locale`: numbers go through [TripsFormatting.plain] (HALF_UP, comma).
 */
object StatsFormatting {

    /** Electric share, e.g. `61,3 %`; `null` → "brak danych" (never "0 %": no `hdc` is not no electricity). */
    fun share(pct: BigDecimal?): String = pct?.let { TripsFormatting.plain(it, 1) + " %" } ?: Texts.STATS_NO_EV_DATA

    /** One row of the electric ranking, e.g. `61,3 % na prądzie · 12,0 km`, with a mark for clipped data. */
    fun rankingRow(row: TripShare): String {
        val clipped = if (row.inconsistent) " " + Texts.STATS_CLIPPED_MARK else ""
        return Texts.statsShareOfTrip(share(row.sharePct)) + clipped + Texts.SEPARATOR +
            TripsFormatting.plain(row.distanceKm, 1) + " km"
    }

    /** `2026` or `zima 2025/26` (December counts to the winter named by January's year). */
    fun periodLabel(stats: PeriodStats): String = when (val season = stats.season) {
        null -> stats.year.toString()
        Season.WINTER -> Texts.SEASON_WINTER + " " + (stats.year - 1) + "/" + (stats.year % 100).toString().padStart(2, '0')
        Season.SPRING -> Texts.SEASON_SPRING + " " + stats.year
        Season.SUMMER -> Texts.SEASON_SUMMER + " " + stats.year
        Season.AUTUMN -> Texts.SEASON_AUTUMN + " " + stats.year
    }

    /**
     * The facts of one period, e.g. `12 tras · 450,0 km · 4,50 l/100 km · 312,40 zł · 69,42 zł/100 km · EV 41,0 %`;
     * missing values are left out, a cost without any price says so.
     */
    fun periodLine(stats: PeriodStats): String {
        val parts = mutableListOf(
            Texts.statsTrips(stats.trips),
            TripsFormatting.plain(stats.distanceKm, 1) + " km",
        )
        stats.lPer100Km?.let { parts += RefuelLogFormatting.consumption(it) }
        parts += stats.costPln?.let { TripsFormatting.money(it) } ?: Texts.NO_PRICE
        stats.costPer100Km?.let { parts += TripsFormatting.plain(it, 2) + " zł/100 km" }
        stats.evSharePct?.let { parts += "EV " + share(it) }
        return parts.joinToString(Texts.SEPARATOR)
    }

    /** `1.01–31.10`, the comparable window of year over year (docs/SPEC.md §17). */
    fun window(until: LocalDate): String =
        "1.01–" + until.dayOfMonth + "." + until.monthValue.toString().padStart(2, '0')
}
