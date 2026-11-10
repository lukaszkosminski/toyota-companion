package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import pl.lk.toyotacompanion.core.domain.PLAN_MIN_TRIPS
import pl.lk.toyotacompanion.core.domain.PlanPopulation
import pl.lk.toyotacompanion.core.domain.PlannedRange
import pl.lk.toyotacompanion.core.domain.PlannedTripResult

/**
 * The text of the trip cost calculator (PRD F52, docs/SPEC.md §52). Pure and independent of `Locale`: numbers go through
 * [TripsFormatting.number]; the range is `ok. 0,5–0,7 l · 3–4 zł`, and the helper line says which trips it was taken from.
 */
object PlannedTripFormatting {
    /** The kilometres as typed (`12,5` or `12.5`); `null` for an empty or wrong value, which gives no result. */
    fun parseKm(text: String): BigDecimal? = parseExpenseAmount(text)

    /** `0,5–0,7 l`, or one value when both ends are the same. */
    fun litres(range: PlannedRange): String = span(range.litresLow, range.litresHigh, 1, " l")

    /** `3–4 zł`, `null` without a price. */
    fun money(range: PlannedRange): String? {
        val low = range.costLow ?: return null
        val high = range.costHigh ?: return null
        return span(low, high, 0, " zł")
    }

    /** `ok. 0,5–0,7 l · 3–4 zł`, or without the money part. */
    fun headline(range: PlannedRange): String = Texts.plannedResult(litres(range), money(range))

    /** `z 37 podobnych tras`, or `z 212 tras tego rodzaju (za mało podobnej długości)` when the whole kind was used. */
    fun helper(range: PlannedRange): String = when (range.population) {
        PlanPopulation.SIMILAR_LENGTH -> Texts.plannedSimilar(range.trips)
        PlanPopulation.WHOLE_KIND -> Texts.plannedWholeKind(range.kindTrips)
    }

    /** The one line for a result that is not a range. */
    fun notRange(result: PlannedTripResult): String? = when (result) {
        PlannedTripResult.BadKm -> Texts.PLANNED_BAD_KM
        is PlannedTripResult.TooFew -> Texts.plannedTooFew(result.trips, PLAN_MIN_TRIPS)
        is PlannedTripResult.Range -> null
    }

    private fun span(low: BigDecimal, high: BigDecimal, places: Int, unit: String): String {
        val a = TripsFormatting.number(low, places)
        return if (low.compareTo(high) == 0) a + unit else a + "–" + TripsFormatting.number(high, places) + unit
    }
}
