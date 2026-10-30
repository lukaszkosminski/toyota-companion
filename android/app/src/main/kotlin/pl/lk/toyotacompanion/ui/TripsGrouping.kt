package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.CostSource
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
 */
data class MonthGroup(
    val month: MonthKey,
    val trips: List<StoredTrip>,
    val totalKm: BigDecimal,
    val totalLiters: BigDecimal,
    val totalCost: BigDecimal?,
    val tripsWithoutCost: Int,
)

/** A trip with no price worth showing (docs/SPEC.md §10.2). */
fun hasNoCost(cost: TripCost?): Boolean =
    cost == null || cost.costPln == null || cost.source == CostSource.NONE

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
        .map { (month, group) ->
            var km = BigDecimal.ZERO
            var liters = BigDecimal.ZERO
            var cost: BigDecimal? = null
            var missing = 0
            for (stored in group) {
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
            MonthGroup(month, group.toList(), km, liters, cost, missing)
        }
}
