package pl.lk.toyotacompanion.core.data

import java.time.Instant

/**
 * Archive of driven trips (PRD F5).
 *
 * Identity is the Toyota `trip.id`: writing an existing id must never create a
 * second row and never invent a different one (docs/SPEC.md §8.2, and the same
 * rule for the archive import in PRD F7).
 */
interface TripRepository {
    /**
     * Insert or replace by [TripRecord.id].
     * Returns the number of ids that were not present before (new rows).
     */
    suspend fun upsertAll(records: List<TripRecord>): Int

    suspend fun byId(id: String): TripRecord?

    /** Ids of trips of [vin] starting in `[from, to]` — used to spot what is already archived. */
    suspend fun idsBetween(vin: String, from: Instant, to: Instant): Set<String>

    /** Trips of [vin] starting at or after [start], newest start first. */
    suspend fun allSince(vin: String, start: Instant): List<TripRecord>

    /** All trips of [vin], oldest start first (input order of `recomputeCosts`, SPEC §5). */
    suspend fun all(vin: String): List<TripRecord>

    /** Rows across every vehicle — the archive is kept even after a VIN change. */
    suspend fun count(): Int

    suspend fun deleteAll()
}
