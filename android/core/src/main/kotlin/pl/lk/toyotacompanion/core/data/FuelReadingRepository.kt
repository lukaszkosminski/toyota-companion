package pl.lk.toyotacompanion.core.data

import java.time.Instant
import pl.lk.toyotacompanion.core.domain.FuelReading

/**
 * Fuel-level telemetry readings (`GET /v3/telemetry`, one row per poll).
 *
 * Toyota exposes no telemetry history, so the archive is built by this app:
 * every synchronization appends the current reading and compares it with the
 * last stored one (docs/SPEC.md §8.3, PRD F1). Identity is `(vin, ts)` — the
 * same reading reported twice (car asleep) must not create a second row.
 */
interface FuelReadingRepository {
    /** Insert unless the same `(vin, ts)` exists. Returns true when a row was added. */
    suspend fun addIfAbsent(reading: FuelReading, vin: String): Boolean

    /** Newest stored reading of [vin] — the `prev` side of `detectRefuel`. */
    suspend fun latest(vin: String): FuelReading?

    /** Readings of [vin] at or after [from], oldest first (charts, F9). */
    suspend fun since(from: Instant, vin: String): List<FuelReading>

    suspend fun count(): Int

    suspend fun deleteAll()
}
