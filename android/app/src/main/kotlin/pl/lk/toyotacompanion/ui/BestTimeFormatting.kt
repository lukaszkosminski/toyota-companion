package pl.lk.toyotacompanion.ui

import pl.lk.toyotacompanion.core.domain.BEST_TIME_MIN_CELLS
import java.math.BigDecimal
import pl.lk.toyotacompanion.core.domain.BEST_TIME_MIN_DIFFERENCE_MINUTES
import pl.lk.toyotacompanion.core.domain.BestTimeCell
import pl.lk.toyotacompanion.core.domain.BestTimeGrid

/**
 * The text of "Najlepsza godzina wyjazdu" (PRD F53, docs/SPEC.md §53). Pure and independent of `Locale`: numbers go through
 * [TripsFormatting.number] and [TripsFormatting.count]. One conclusion line per route, and one row per cell that has data.
 */
object BestTimeFormatting {
    /** `robocze 9–12` / `weekdays 9–12`: the day kind and the band, the words a reader needs to pick a time. */
    fun cellName(cell: BestTimeCell): String = Texts.bestTimeDay(cell.day) + " " + Texts.bestTimeBand(cell.band)

    /** The conclusion of a route: fastest and slowest median, or "no clear difference" when they are less than two minutes apart. */
    fun conclusion(grid: BestTimeGrid): String? {
        val fastest = grid.fastest ?: return null
        val slowest = grid.slowest ?: return null
        if (slowest.medianMinutes - fastest.medianMinutes < BEST_TIME_MIN_DIFFERENCE_MINUTES) {
            return Texts.bestTimeNoDifference(fastest.medianMinutes.toString())
        }
        return Texts.bestTimeConclusion(
            cellName(fastest),
            fastest.medianMinutes.toString(),
            cellName(slowest),
            slowest.medianMinutes.toString(),
        )
    }

    /** One line under the conclusion when the route has too few cells for a comparison; `null` when it has two or more. */
    fun shortNote(grid: BestTimeGrid): String? = when {
        grid.cells.size >= BEST_TIME_MIN_CELLS -> null
        grid.cells.isEmpty() -> Texts.BEST_TIME_NOT_ENOUGH
        else -> Texts.BEST_TIME_ONE_CELL
    }

    /** The value on the right of a row: the median drive time, `17 min`. */
    fun medianValue(cell: BestTimeCell): String = Texts.bestTimeMinutes(cell.medianMinutes)

    /** The bar of a row is its drive time: the slowest median is the full bar, a faster one is shorter. 0 for no time. */
    fun fraction(cell: BestTimeCell, slowest: Long): Float {
        if (cell.medianMinutes <= 0L || slowest <= 0L) return 0f
        return (cell.medianMinutes.toFloat() / slowest.toFloat()).coerceIn(0f, 1f)
    }

    /** One helper line of a row: `5 przejazdów · 6,20 l/100 km`; the litres part is left out without any distance. */
    fun helper(cell: BestTimeCell): String = listOfNotNull(
        Texts.bestTimeTrips(cell.trips),
        cell.lPer100Km?.let { Texts.trafficConsumption(TripsFormatting.number(it, 2)) },
    ).joinToString(Texts.SEPARATOR)

    /** `Odcinek 2 · 9,4 km · 14 przejazdów`: the route as the screen numbers it, its typical length and its trips. */
    fun routeTitle(number: Int, typicalKm: BigDecimal, trips: Int): String =
        Texts.bestTimeRouteTitle(number, TripsFormatting.number(typicalKm, 1) + " km", Texts.bestTimeTrips(trips))
}
