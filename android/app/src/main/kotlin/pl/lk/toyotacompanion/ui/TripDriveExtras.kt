package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import pl.lk.toyotacompanion.core.data.TripRecord

private val HUNDRED = BigDecimal(100)
private val THOUSAND = BigDecimal(1000)

/**
 * The extra line of a trip row (docs/SPEC.md §41): the motorway kilometres and the kilometres over the speed limit, both as
 * Toyota reports them, e.g. `autostrada 4,2 km · przekroczenia 1,3 km (9 %)`. The share is of the trip's distance. `null` when
 * the trip has neither (nothing, 0 or not reported), so most rows get no extra line.
 */
fun tripDriveExtrasLine(record: TripRecord): String? {
    val parts = ArrayList<String>(2)
    val highway = record.highwayLengthM
    if (highway != null && highway > 0) {
        parts += Texts.driveHighway(TripsFormatting.number(km(highway), 1))
    }
    val overspeed = record.overspeedLengthM
    if (overspeed != null && overspeed > 0) {
        parts += Texts.driveOverspeed(TripsFormatting.number(km(overspeed), 1), sharePct(overspeed, record.trip.distanceKm))
    }
    return if (parts.isEmpty()) null else parts.joinToString(Texts.SEPARATOR)
}

private fun km(meters: Long): BigDecimal = BigDecimal(meters).divide(THOUSAND, 1, RoundingMode.HALF_UP)

/** Whole percent of the trip's distance, capped at 100 (Toyota's two lengths come from different measurements). */
private fun sharePct(overspeedM: Long, distanceKm: Double): String {
    if (!distanceKm.isFinite() || distanceKm <= 0.0) return "0"
    val share = BigDecimal(overspeedM).multiply(HUNDRED)
        .divide(BigDecimal(distanceKm * 1000.0), 0, RoundingMode.HALF_UP)
    return TripsFormatting.number(share.min(HUNDRED), 0)
}
