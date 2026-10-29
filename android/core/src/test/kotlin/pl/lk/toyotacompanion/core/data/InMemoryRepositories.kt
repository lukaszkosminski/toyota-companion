package pl.lk.toyotacompanion.core.data

import java.time.Instant
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.TripCost

/**
 * In-memory implementations of every repository contract, used only by tests:
 * `core` must stay free of Room (docs/ARCHITECTURE.md), and the app module
 * re-runs the same contract tests against Room (Etap B).
 *
 * Behavioural rules mirrored from the intended Room mapping:
 * - trips: identity `trip.id`, upsert replaces the row;
 * - refuels/readings: insert-or-ignore on their identity;
 * - vehicles: `firstSeen` survives an update.
 */
class InMemoryTripRepository : TripRepository {
    private val rows = LinkedHashMap<String, TripRecord>()

    override suspend fun upsertAll(records: List<TripRecord>): Int {
        var added = 0
        for (record in records) {
            if (!rows.containsKey(record.id)) added++
            rows[record.id] = record
        }
        return added
    }

    override suspend fun byId(id: String): TripRecord? = rows[id]

    override suspend fun idsBetween(vin: String, from: Instant, to: Instant): Set<String> =
        rows.values
            .filter { it.vin == vin && !it.trip.start.isBefore(from) && !it.trip.start.isAfter(to) }
            .map { it.id }
            .toSet()

    override suspend fun allSince(vin: String, start: Instant): List<TripRecord> =
        rows.values
            .filter { it.vin == vin && !it.trip.start.isBefore(start) }
            .sortedByDescending { it.trip.start }

    override suspend fun all(vin: String): List<TripRecord> =
        rows.values.filter { it.vin == vin }.sortedBy { it.trip.start }

    override suspend fun count(): Int = rows.size

    override suspend fun deleteAll() = rows.clear()
}

class InMemoryRefuelRepository : RefuelRepository {
    private data class Row(val refuel: Refuel, val vin: String)

    private val rows = LinkedHashMap<String, Row>()

    override suspend fun addIfAbsent(refuel: Refuel, vin: String): Boolean {
        if (rows.containsKey(refuel.id)) return false
        rows[refuel.id] = Row(refuel, vin)
        return true
    }

    override suspend fun update(refuel: Refuel) {
        val existing = rows[refuel.id] ?: return
        rows[refuel.id] = Row(refuel, existing.vin)
    }

    override suspend fun byId(id: String): Refuel? = rows[id]?.refuel

    override suspend fun pending(vin: String?): List<Refuel> =
        rows.values
            .filter { it.refuel.status == RefuelStatus.PENDING && (vin == null || it.vin == vin) }
            .map { it.refuel }
            .sortedByDescending { it.ts }

    override suspend fun confirmedUpTo(moment: Instant, vin: String?): List<Refuel> =
        ofVin(vin)
            .filter { it.status == RefuelStatus.CONFIRMED && !it.ts.isAfter(moment) }
            .sortedWith(compareBy({ it.ts }, { it.id }))

    override suspend fun all(vin: String?): List<Refuel> = ofVin(vin).sortedBy { it.ts }

    override suspend fun count(): Int = rows.size

    override suspend fun deleteAll() = rows.clear()

    private fun ofVin(vin: String?): List<Refuel> =
        rows.values.filter { vin == null || it.vin == vin }.map { it.refuel }
}

class InMemoryFuelReadingRepository : FuelReadingRepository {
    private data class Row(val reading: FuelReading, val vin: String)

    private val rows = LinkedHashMap<Pair<String, Instant>, Row>()

    override suspend fun addIfAbsent(reading: FuelReading, vin: String): Boolean {
        val key = vin to reading.ts
        if (rows.containsKey(key)) return false
        rows[key] = Row(reading, vin)
        return true
    }

    override suspend fun latest(vin: String): FuelReading? =
        rows.values.filter { it.vin == vin }.maxByOrNull { it.reading.ts }?.reading

    override suspend fun since(from: Instant, vin: String): List<FuelReading> =
        rows.values
            .filter { it.vin == vin && !it.reading.ts.isBefore(from) }
            .map { it.reading }
            .sortedBy { it.ts }

    override suspend fun count(): Int = rows.size

    override suspend fun deleteAll() = rows.clear()
}

class InMemoryCostRepository : CostRepository {
    private val rows = LinkedHashMap<String, TripCost>()

    override suspend fun saveAll(costs: List<TripCost>): Int {
        costs.forEach { rows[it.tripId] = it }
        return costs.size
    }

    override suspend fun forTrip(tripId: String): TripCost? = rows[tripId]

    override suspend fun all(): List<TripCost> = rows.values.toList()

    override suspend fun count(): Int = rows.size

    override suspend fun deleteAll() = rows.clear()
}

class InMemoryVehicleRepository : VehicleRepository {
    private val rows = LinkedHashMap<String, VehicleRecord>()

    override suspend fun upsert(vehicle: VehicleRecord) {
        val previous = rows[vehicle.vin]
        rows[vehicle.vin] = if (previous == null) {
            vehicle
        } else {
            vehicle.copy(firstSeen = previous.firstSeen)
        }
    }

    override suspend fun byVin(vin: String): VehicleRecord? = rows[vin]

    override suspend fun all(): List<VehicleRecord> = rows.values.toList()

    override suspend fun count(): Int = rows.size

    override suspend fun deleteAll() = rows.clear()
}

class InMemorySyncStateRepository : SyncStateRepository {
    private var current = SyncState()

    override suspend fun get(): SyncState = current

    override suspend fun save(state: SyncState) {
        current = state
    }

    override suspend fun deleteAll() {
        current = SyncState()
    }
}

/** Everything wired up for one [pl.lk.toyotacompanion.core.sync.SyncEngine] test. */
class InMemoryStores(
    val trips: InMemoryTripRepository = InMemoryTripRepository(),
    val refuels: InMemoryRefuelRepository = InMemoryRefuelRepository(),
    val readings: InMemoryFuelReadingRepository = InMemoryFuelReadingRepository(),
    val costs: InMemoryCostRepository = InMemoryCostRepository(),
    val vehicles: InMemoryVehicleRepository = InMemoryVehicleRepository(),
    val state: InMemorySyncStateRepository = InMemorySyncStateRepository(),
)
