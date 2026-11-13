package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.co2Kg
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.TripCost
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/**
 * Year and month of a group header (docs/SPEC.md §10.2). The archive spans years,
 * so a bare month number cannot be the key.
 */
data class MonthKey(val year: Int, val month: Int) : Comparable<MonthKey> {
    override fun compareTo(other: MonthKey): Int =
        if (year != other.year) year.compareTo(other.year) else month.compareTo(other.month)
}

/**
 * One month of the trips list: a header with sums and the trips of that month (docs/SPEC.md §10.2).
 *
 * @param totalCost sum of the costs of the trips that have one; `null` when no trip of
 * the group has a cost — the header then says "brak ceny" instead of "0,00 zł"
 * @param tripsWithoutCost how many trips of the group have no price (the "bez ceny: N" note)
 * @param co2Kg estimated CO2 of the burned litres of the month, kg with one decimal (PRD F51, docs/SPEC.md §51); from the
 * unrounded [totalLiters], 0 for a month of trips on the battery only
 */
data class MonthGroup(
    val month: MonthKey,
    val trips: List<StoredTrip>,
    val totalKm: BigDecimal,
    val totalLiters: BigDecimal,
    val totalCost: BigDecimal?,
    val tripsWithoutCost: Int,
    val co2Kg: BigDecimal,
)

/** A trip with no price worth showing (docs/SPEC.md §10.2). */
fun hasNoCost(cost: TripCost?): Boolean =
    cost == null || cost.costPln == null || cost.source == CostSource.NONE

/** What the trips list shows: the whole month groups, or only the trips without a price (docs/SPEC.md §10.3). */
enum class TripsFilter {
    ALL,
    WITHOUT_COST,
}

/**
 * Groups trips by month (docs/SPEC.md §10.2) — pure function, no Compose and no database:
 * the zone is a parameter, so a test does not depend on the phone clock. The month comes
 * from `trip.start` in that zone, which is why a test guards the midnight boundary in a
 * zone other than UTC.
 *
 * Newest month first; inside a month the input order is kept, i.e. the order from
 * [pl.lk.toyotacompanion.core.sync.SyncEngine.trips] — newest trip first. The input list
 * is never re-sorted here.
 */
fun groupTripsByMonth(trips: List<StoredTrip>, zone: ZoneId): List<MonthGroup> {
    val byMonth = LinkedHashMap<MonthKey, MutableList<StoredTrip>>()
    for (stored in trips) {
        val local = stored.record.trip.start.atZone(zone)
        byMonth.getOrPut(MonthKey(local.year, local.monthValue)) { mutableListOf() }.add(stored)
    }
    return byMonth.entries
        .sortedByDescending { it.key }
        .map { (month, group) -> monthGroup(month, group.toList()) }
}

/**
 * Applies [filter] to already grouped trips (docs/SPEC.md §10.3). `WITHOUT_COST` keeps
 * only the trips whose row says "brak ceny"; a month without such a trip disappears.
 * Every kept month is rebuilt with [monthGroup], so its header, sums and CO2 describe
 * only the rows that remain on screen. Order (months and trips) is preserved.
 */
fun filterTrips(groups: List<MonthGroup>, filter: TripsFilter): List<MonthGroup> {
    if (filter == TripsFilter.ALL) return groups
    return groups.mapNotNull { group ->
        val kept = group.trips.filter { hasNoCost(it.cost) }
        if (kept.isEmpty()) null else monthGroup(group.month, kept)
    }
}

/** One month header with sums computed from exactly [trips]; shared by grouping and filtering. */
private fun monthGroup(month: MonthKey, trips: List<StoredTrip>): MonthGroup {
    var km = BigDecimal.ZERO
    var liters = BigDecimal.ZERO
    var cost: BigDecimal? = null
    var missing = 0
    for (stored in trips) {
        // Distance is a Double — via toBigDecimal as the domain port does, no binary summation.
        km = km.add(toBigDecimal(stored.record.trip.distanceKm))
        liters = liters.add(stored.record.trip.fuelL)
        val value = stored.cost?.costPln
        if (hasNoCost(stored.cost) || value == null) {
            missing++
        } else {
            cost = cost?.add(value) ?: value
        }
    }
    return MonthGroup(month, trips, km, liters, cost, missing, co2Kg(liters))
}

/**
 * The trips whose row shows the price per litre (docs/SPEC.md §10.3). The list reads newest first, so the price stands on the **newest**
 * trip of every run of equal prices: the line under the cost says `6,30 zł/l` only when it differs from the line of the chronologically
 * next trip (by start, then id, across months), when the trip is the newest of all, and always on the newest trip of each month, so the
 * top of the screen and the top of every month show a price. A trip with no price line (no price) shows none; "cena domyślna" is part of
 * the line, so a change between a refuel price and the default one counts as a change.
 */
fun tripIdsWithPriceLine(groups: List<MonthGroup>): Set<String> {
    val order = compareBy<StoredTrip>({ it.record.trip.start }, { it.record.id })
    val ordered = groups.flatMap { it.trips }.sortedWith(order)
    val newestOfMonth = groups.mapNotNull { group -> group.trips.maxWithOrNull(order)?.record?.id }.toSet()
    val shown = LinkedHashSet<String>()
    for (index in ordered.indices) {
        val stored = ordered[index]
        val line = TripsFormatting.priceLine(stored) ?: continue
        val next = ordered.getOrNull(index + 1)?.let { TripsFormatting.priceLine(it) }
        if (stored.record.id in newestOfMonth || index == ordered.lastIndex || line != next) shown += stored.record.id
    }
    return shown
}
