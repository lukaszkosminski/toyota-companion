package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.OwnershipCost
import pl.lk.toyotacompanion.core.domain.co2Kg
import pl.lk.toyotacompanion.core.domain.ownershipCost
import pl.lk.toyotacompanion.core.domain.ownershipMonths
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/**
 * One year of driving on one page (docs/SPEC.md §29): totals, the best, worst and dearest month and the change against the
 * year before. Months are counted in the zone of the trips list. [costPln] and [costPer100] are `null` without a single
 * priced trip, never 0 zł; [bestMonth] and [worstMonth] are months of at least [CONSUMPTION_MIN_MONTH_KM].
 */
data class YearSummary(
    val year: Int,
    val trips: Int,
    val distanceKm: BigDecimal,
    val fuelL: BigDecimal,
    val lPer100: BigDecimal?,
    val costPln: BigDecimal?,
    val costPer100: BigDecimal?,
    val tripsWithoutCost: Int,
    /** Electric km (each trip cut to its own distance) over all km of the year, whole percent; `null` without any. */
    val evSharePct: BigDecimal?,
    val daysDriven: Int,
    val longestTripKm: BigDecimal?,
    val longestTripStart: Instant?,
    val bestMonth: MonthConsumption?,
    val worstMonth: MonthConsumption?,
    val dearestMonth: MonthCost?,
    /** Estimated CO2 of the burned litres of the year, kg with one decimal; from the unrounded litres (PRD F51, docs/SPEC.md §51). */
    val co2Kg: BigDecimal,
)

/** The years with at least one usable trip, newest first. */
fun yearsWithTrips(trips: List<StoredTrip>, zone: ZoneId): List<Int> =
    usableForYear(trips).map { it.record.trip.start.atZone(zone).year }.distinct().sortedDescending()

private fun usableForYear(trips: List<StoredTrip>): List<StoredTrip> = trips.filter {
    val trip = it.record.trip
    trip.distanceKm.isFinite() && trip.distanceKm > 0.0 && trip.fuelL.signum() >= 0
}

/** The summary of [year], or `null` when it has no usable trip. */
fun yearSummary(trips: List<StoredTrip>, year: Int, zone: ZoneId): YearSummary? {
    val ofYear = usableForYear(trips).filter { it.record.trip.start.atZone(zone).year == year }
    if (ofYear.isEmpty()) return null
    var km = BigDecimal.ZERO
    var fuel = BigDecimal.ZERO
    var electric = BigDecimal.ZERO
    var pricedKm = BigDecimal.ZERO
    var cost: BigDecimal? = null
    var without = 0
    for (stored in ofYear) {
        val trip = stored.record.trip
        val distance = toBigDecimal(trip.distanceKm)
        km = km.add(distance)
        fuel = fuel.add(trip.fuelL)
        val ev = trip.evDistanceKm
        if (ev != null && ev.isFinite() && ev > 0.0) electric = electric.add(toBigDecimal(minOf(ev, trip.distanceKm)))
        val value = stored.cost?.costPln
        if (value == null || hasNoCost(stored.cost)) {
            without++
        } else {
            cost = cost?.add(value) ?: value
            pricedKm = pricedKm.add(distance)
        }
    }
    val view = consumptionView(ofYear, zone)
    val longest = ofYear.maxByOrNull { it.record.trip.distanceKm }
    val hundred = BigDecimal(100)
    return YearSummary(
        year = year,
        trips = ofYear.size,
        distanceKm = km.setScale(1, RoundingMode.HALF_UP),
        fuelL = fuel.setScale(1, RoundingMode.HALF_UP),
        lPer100 = fuel.multiply(hundred).divide(km, 2, RoundingMode.HALF_UP),
        costPln = cost?.setScale(2, RoundingMode.HALF_UP),
        costPer100 = if (cost != null && pricedKm.signum() > 0) cost.multiply(hundred).divide(pricedKm, 2, RoundingMode.HALF_UP) else null,
        tripsWithoutCost = without,
        evSharePct = if (electric.signum() > 0) electric.multiply(hundred).divide(km, 0, RoundingMode.HALF_UP) else null,
        daysDriven = ofYear.map { it.record.trip.start.atZone(zone).toLocalDate() }.distinct().size,
        longestTripKm = longest?.let { toBigDecimal(it.record.trip.distanceKm).setScale(1, RoundingMode.HALF_UP) },
        longestTripStart = longest?.record?.trip?.start,
        bestMonth = view.months.minByOrNull { it.lPer100 },
        worstMonth = view.months.maxByOrNull { it.lPer100 },
        dearestMonth = view.monthCosts.filter { it.costPln != null }.maxByOrNull { it.costPln!! },
        co2Kg = co2Kg(fuel),
    )
}

/**
 * The lines of the page and of the text that is shared (docs/SPEC.md §29): plain Polish, no coordinates, no VIN. [previous] is
 * the year before, when it has trips, for the change of kilometres, litres and money.
 */
fun yearSummaryLines(summary: YearSummary, previous: YearSummary?, zone: ZoneId): List<String> {
    val lines = ArrayList<String>()
    lines += Texts.yearTotals(summary.trips, TripsFormatting.number(summary.distanceKm, 0), TripsFormatting.number(summary.fuelL, 0))
    summary.lPer100?.let { lines += Texts.yearAverage(TripsFormatting.number(it, 2)) }
    lines += summary.costPln?.let {
        Texts.yearCost(TripsFormatting.money(it), summary.costPer100?.let { c -> TripsFormatting.money(c) }) +
            if (summary.tripsWithoutCost > 0) Texts.SEPARATOR + Texts.tripsWithoutCost(summary.tripsWithoutCost) else ""
    } ?: Texts.yearNoCost
    lines += Texts.co2(TripsFormatting.co2(summary.co2Kg))
    summary.evSharePct?.let { lines += Texts.yearEv(TripsFormatting.number(it, 0)) }
    lines += Texts.yearDays(summary.daysDriven)
    val start = summary.longestTripStart
    val longest = summary.longestTripKm
    if (longest != null && start != null) {
        lines += Texts.yearLongest(TripsFormatting.number(longest, 1), RefuelFormatting.time(start, zone))
    }
    summary.bestMonth?.let { lines += Texts.yearBest(TripsFormatting.monthLabel(it.month), TripsFormatting.number(it.lPer100, 2)) }
    summary.worstMonth?.takeIf { it.month != summary.bestMonth?.month }?.let {
        lines += Texts.yearWorst(TripsFormatting.monthLabel(it.month), TripsFormatting.number(it.lPer100, 2))
    }
    summary.dearestMonth?.let {
        lines += Texts.yearDearest(TripsFormatting.monthLabel(it.month), TripsFormatting.money(requireNotNull(it.costPln)))
    }
    if (previous != null) {
        lines += Texts.yearVersus(
            previous.year,
            percentChange(summary.distanceKm, previous.distanceKm),
            percentChange(summary.costPln, previous.costPln),
            summary.lPer100?.let { now -> previous.lPer100?.let { before -> TripsFormatting.signed(now.subtract(before), 2) } },
        )
    }
    return lines
}

/**
 * Fuel and everything else the car cost in one year (PRD F38, docs/SPEC.md §39): the fuel of the priced trips of [summary]
 * plus the expenses dated in that year, averaged over the months from the first trip or expense of the year to December (to
 * [today]'s month in the current year). [topCategories] are the three biggest expense categories of the year.
 */
data class YearOwnership(val cost: OwnershipCost, val months: Int, val topCategories: List<CategoryTotal>)

/** Number of expense categories listed under the total cost (docs/SPEC.md §39). */
const val OWNERSHIP_TOP_CATEGORIES = 3

/** The total cost of [year]; `null` when the year has neither a priced trip nor an expense. */
fun yearOwnership(
    trips: List<StoredTrip>,
    expenses: List<Expense>,
    year: Int,
    zone: ZoneId,
    today: LocalDate,
): YearOwnership? {
    val summary = yearSummary(trips, year, zone)
    val ofYear = expenses.filter { it.date.year == year }
    val tripMonths = trips.map { it.record.trip.start.atZone(zone) }.filter { it.year == year }.map { it.monthValue }
    val firstMonth = (tripMonths + ofYear.map { it.date.monthValue }).minOrNull() ?: return null
    val spent = ofYear.fold(BigDecimal.ZERO) { sum, e -> sum.add(e.amount) }
    val months = ownershipMonths(year, firstMonth, today.year, today.monthValue)
    val cost = ownershipCost(
        fuelCost = summary?.costPln,
        distanceKm = summary?.distanceKm ?: BigDecimal.ZERO,
        expenses = spent,
        months = months,
    ) ?: return null
    return YearOwnership(cost, months, categoryTotals(ofYear).take(OWNERSHIP_TOP_CATEGORIES))
}

/** The total-cost lines of the page and of the shared text (docs/SPEC.md §39); empty without [ownership]. */
fun ownershipLines(ownership: YearOwnership?, previous: YearOwnership?): List<String> {
    val cost = ownership?.cost ?: return emptyList()
    val lines = ArrayList<String>()
    lines += Texts.ownershipTotal(
        TripsFormatting.money(cost.totalPln),
        cost.perKmPln?.let { TripsFormatting.money(it) },
        cost.per100KmPln?.let { TripsFormatting.money(it) },
    )
    cost.perMonthPln?.let { lines += Texts.ownershipPerMonth(TripsFormatting.money(it), ownership.months) }
    lines += Texts.ownershipSplit(
        TripsFormatting.money(cost.fuelPln),
        TripsFormatting.money(cost.expensesPln),
        cost.fuelSharePct?.let { TripsFormatting.number(it, 0) },
    )
    if (ownership.topCategories.isNotEmpty()) {
        lines += Texts.ownershipTop(
            ownership.topCategories.joinToString(", ") { Texts.expenseCategoryName(it.category) + " " + TripsFormatting.money(it.total) },
        )
    }
    if (previous != null) percentChange(cost.totalPln, previous.cost.totalPln)?.let { lines += Texts.ownershipVersus(it) }
    return lines
}

/** `+12 %`, `-4 %`, `0 %`; `null` when either side is missing or the base is zero. */
fun percentChange(now: BigDecimal?, before: BigDecimal?): String? {
    if (now == null || before == null || before.signum() <= 0) return null
    val change = now.subtract(before).multiply(BigDecimal(100)).divide(before, 0, RoundingMode.HALF_UP)
    return (if (change.signum() > 0) "+" else "") + change.toPlainString() + " %"
}

/** The text handed to the share sheet: a title and the lines. */
fun yearSummaryShareText(
    summary: YearSummary,
    previous: YearSummary?,
    zone: ZoneId,
    ownership: YearOwnership? = null,
    previousOwnership: YearOwnership? = null,
): String = (
    listOf(Texts.yearShareTitle(summary.year)) + yearSummaryLines(summary, previous, zone) + ownershipLines(ownership, previousOwnership)
    ).joinToString("\n")
