package pl.lk.toyotacompanion.core.data

import pl.lk.toyotacompanion.core.domain.TripCost

/**
 * Computed trip costs (docs/SPEC.md §4, §5; PRD F3, F4).
 *
 * Costs are derived data: they are rewritten in full whenever a refuel price is
 * confirmed or a synchronization ends. Trip identity is `trip.id`, so a trip
 * never has two cost rows.
 */
interface CostRepository {
    /** Insert or replace one cost per `TripCost.tripId`. Returns the number of rows written. */
    suspend fun saveAll(costs: List<TripCost>): Int

    suspend fun forTrip(tripId: String): TripCost?

    /** Every stored cost (CSV export PRD F6, statistics F10). */
    suspend fun all(): List<TripCost>

    suspend fun count(): Int

    suspend fun deleteAll()
}
