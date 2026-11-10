package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import pl.lk.toyotacompanion.core.domain.BandStats
import pl.lk.toyotacompanion.core.domain.LengthBand
import pl.lk.toyotacompanion.core.domain.ShortTripSummary

/**
 * The text of the card "Krótkie trasy i zimny silnik" (PRD F50, docs/SPEC.md §50). Pure and independent of `Locale`: every number goes
 * through [TripsFormatting.number], so a long number never breaks. Each band is one meter row (name, litres per 100 km, bar) with one short
 * helper line under it; the cold start has a block of its own.
 */
object ShortTripsFormatting {
    /** The value on the right of a band row: `5,84 l/100 km`, or "za mało" below the minimum of trips. */
    fun bandValue(row: BandStats): String =
        row.lPer100Km?.let { Texts.trafficConsumption(TripsFormatting.number(it, 2)) } ?: Texts.SHORT_TOO_FEW

    /** The one helper line of a band: `41 tras (23 %) · 9 % km · +1,62 l/100 km`; the excess only for the short bands that have one. */
    fun bandLine(row: BandStats): String {
        val parts = ArrayList<String>(3)
        parts += Texts.shortTripsOfShare(row.trips, TripsFormatting.number(row.tripSharePct, 0))
        parts += Texts.shortKmShare(TripsFormatting.number(row.kmSharePct, 0))
        row.excessLPer100Km?.let { parts += Texts.shortExcessRate(TripsFormatting.signed(it, 2)) }
        return parts.joinToString(Texts.SEPARATOR)
    }

    /** `Krótkie trasy (poniżej 10 km): 38 % tras, 11 % km`; `null` without any used trip. */
    fun headline(summary: ShortTripSummary): String? {
        val trips = summary.shortTripSharePct ?: return null
        val km = summary.shortKmSharePct ?: return null
        return Texts.shortHeadline(TripsFormatting.number(trips, 0), TripsFormatting.number(km, 0))
    }

    /**
     * The total of the short bands, only when at least one band could be compared with the reference: `Nadwyżka w archiwum: 14,2 l · 91,40 zł`
     * when they burn more, "Nie zużywają więcej paliwa niż trasy od 10 km" when not (a negative or zero total is no excess). The money part is
     * left out without a price in every counted band.
     */
    fun excessLine(summary: ShortTripSummary): String? {
        val litres = summary.totalExcessL ?: return null
        if (litres.signum() <= 0) return Texts.SHORT_NO_EXCESS
        val money = summary.totalExcessPln?.takeIf { it.signum() > 0 }?.let { TripsFormatting.money(it) }
        return Texts.shortExcess(TripsFormatting.number(litres, 1) + " l", money)
    }

    /** `Odniesienie – trasy od 10 km`, with the number of trips. */
    fun referenceLabel(): String = Texts.SHORT_REFERENCE

    /**
     * One band of the cold-start block: `2–5 km: zimny 6,10 · ciepły 5,60 l/100 km (+0,50)` when both sides have enough trips; `null` otherwise.
     */
    fun coldLine(row: BandStats): String? {
        val split = row.coldSplit ?: return null
        return Texts.shortColdRow(
            Texts.shortBandName(row.band),
            TripsFormatting.number(split.coldLPer100Km, 2),
            TripsFormatting.number(split.warmLPer100Km, 2),
            TripsFormatting.signed(split.differenceLPer100Km, 2),
        )
    }

    /** The bands of the cold-start block, in the order of the screen. */
    fun coldLines(summary: ShortTripSummary): List<String> = summary.bands.mapNotNull { coldLine(it) }

    /** The bar of a band as a share of the highest litres per 100 km on the card; 0 for a band without a number. */
    fun fraction(value: BigDecimal?, highest: BigDecimal): Float {
        if (value == null || highest.signum() <= 0) return 0f
        return (value.toFloat() / highest.toFloat()).coerceIn(0f, 1f)
    }

    /** The highest rate of the bands and of the reference, the scale of the bars. */
    fun highest(summary: ShortTripSummary): BigDecimal =
        (summary.bands.mapNotNull { it.lPer100Km } + listOfNotNull(summary.reference?.lPer100Km)).maxOrNull() ?: BigDecimal.ZERO

    /** The name of a band for the screen. */
    fun bandName(band: LengthBand): String = Texts.shortBandName(band)
}
