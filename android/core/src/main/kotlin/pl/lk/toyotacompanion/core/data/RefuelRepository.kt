package pl.lk.toyotacompanion.core.data

import java.time.Instant
import pl.lk.toyotacompanion.core.domain.Refuel

/**
 * Refuel journal (PRD F1, F2, F9).
 *
 * Identity is the deterministic `refuel-<UTC ISO-8601>` id from docs/SPEC.md §1,
 * which makes re-running a synchronization idempotent. Methods that read take a
 * `vin` because the archive may hold more than one vehicle; the domain [Refuel]
 * itself has no vehicle field.
 */
interface RefuelRepository {
    /** Insert unless [Refuel.id] already exists. Returns true when a row was added. */
    suspend fun addIfAbsent(refuel: Refuel, vin: String): Boolean

    /**
     * Replace the stored row after `confirmRefuel`/`dismissRefuel` (PRD F2).
     * The vehicle assignment is kept.
     */
    suspend fun update(refuel: Refuel)

    suspend fun byId(id: String): Refuel?

    /** Waiting for a price, newest first (the notification badge). */
    suspend fun pending(vin: String? = null): List<Refuel>

    /** Confirmed refuels up to and including [moment], ordered `(ts, id)` as docs/SPEC.md §4 expects. */
    suspend fun confirmedUpTo(moment: Instant, vin: String? = null): List<Refuel>

    /** All refuels of [vin] (or of every vehicle when null), oldest first — input for `recomputeCosts`. */
    suspend fun all(vin: String? = null): List<Refuel>

    suspend fun count(): Int

    suspend fun deleteAll()
}
