package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import pl.lk.toyotacompanion.core.domain.EconomyInterval
import pl.lk.toyotacompanion.core.domain.EconomyStatus
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel

/**
 * Polish text of the refuel log (docs/SPEC.md §15.2). Pure and independent of
 * `Locale`: every number goes through [TripsFormatting.plain] (HALF_UP, comma).
 */
object RefuelLogFormatting {

    /** Fuel economy, e.g. `4,82 l/100 km`. */
    fun consumption(value: BigDecimal): String = TripsFormatting.plain(value, 2) + " l/100 km"

    /**
     * Difference of the actual economy against the computer, e.g. `+6,9 %` or `−3,2 %`
     * (a real minus sign); zero has no sign.
     */
    fun difference(pct: BigDecimal): String {
        val sign = when (pct.signum()) {
            1 -> "+"
            -1 -> "\u2212"
            else -> ""
        }
        return sign + TripsFormatting.plain(pct.abs(), 1) + " %"
    }

    /** Distance with one decimal, e.g. `612,4 km`. */
    fun distance(value: BigDecimal): String = TripsFormatting.plain(value, 1) + " km"

    /** Liters of one refuel and where they come from, e.g. `30,5 l z paragonu`; `null` without liters. */
    fun litersLine(refuel: Refuel): String? {
        val liters = refuel.liters ?: return null
        val source = when (refuel.litersSource) {
            LitersSource.RECEIPT -> Texts.LOG_SOURCE_RECEIPT
            LitersSource.DERIVED -> Texts.LOG_SOURCE_DERIVED
            LitersSource.ESTIMATE -> Texts.LOG_SOURCE_ESTIMATE
            null -> null
        }
        val full = if (refuel.filledToFull == true) Texts.SEPARATOR + Texts.REFUEL_FILLED_FULL.lowercase() else ""
        return RefuelFormatting.liters(liters) + (source?.let { " $it" } ?: "") + full
    }

    /** The economy of a closed interval, or why there is none (docs/SPEC.md §15.1). */
    fun economyLine(interval: EconomyInterval): String {
        val actual = interval.actualLPer100Km
        val computer = interval.computerLPer100Km
        val difference = interval.differencePct
        return when (interval.status) {
            EconomyStatus.OK -> if (actual != null && computer != null && difference != null) {
                Texts.economyCompared(consumption(actual), consumption(computer), difference(difference))
            } else {
                Texts.ECONOMY_NO_TRIPS
            }
            EconomyStatus.OPEN -> Texts.ECONOMY_OPEN
            EconomyStatus.PENDING_REFUEL -> Texts.ECONOMY_PENDING
            EconomyStatus.DISMISSED_REFUEL -> Texts.ECONOMY_DISMISSED
            EconomyStatus.MISSING_RECEIPT -> Texts.ECONOMY_MISSING_RECEIPT
            EconomyStatus.NO_TRIPS -> Texts.ECONOMY_NO_TRIPS
            EconomyStatus.TOO_SHORT -> Texts.ECONOMY_TOO_SHORT
        }
    }

    /**
     * The facts under the economy line: trip distance, the odometer (informational
     * only) and the EV share of a hybrid, e.g. `612,4 km tras · licznik 610,0 km · w tym EV 70,3 km`.
     */
    fun detailLine(interval: EconomyInterval): String {
        val parts = mutableListOf(Texts.economyTrips(distance(interval.distanceKm)))
        interval.odometerKm?.let { parts += Texts.economyOdometer(distance(it)) }
        if (interval.evDistanceKm.signum() > 0) parts += Texts.economyEv(distance(interval.evDistanceKm))
        if (interval.status == EconomyStatus.OPEN) {
            interval.computerLPer100Km?.let { parts += Texts.economyComputerSoFar(consumption(it)) }
        }
        return parts.joinToString(Texts.SEPARATOR)
    }
}
