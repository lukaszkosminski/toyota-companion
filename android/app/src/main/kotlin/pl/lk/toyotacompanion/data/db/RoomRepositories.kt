package pl.lk.toyotacompanion.data.db

import java.time.Instant
import pl.lk.toyotacompanion.core.data.CostRepository
import pl.lk.toyotacompanion.core.data.FuelReadingRepository
import pl.lk.toyotacompanion.core.data.RefuelRepository
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.SyncStateRepository
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.TripRepository
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.data.VehicleRepository
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.TripCost

/** Room implementations of the `core.data` repositories; semantics follow `RepositoryContract`. */

class RoomTripRepository(private val dao: TripDao) : TripRepository {
    override suspend fun upsertAll(records: List<TripRecord>): Int = dao.upsertAll(records.map { it.toEntity() })

    override suspend fun byId(id: String): TripRecord? = dao.byId(id)?.toRecord()

    override suspend fun idsBetween(vin: String, from: Instant, to: Instant): Set<String> =
        dao.idsBetween(vin, from, to).toSet()

    override suspend fun allSince(vin: String, start: Instant): List<TripRecord> =
        dao.allSince(vin, start).map { it.toRecord() }

    override suspend fun all(vin: String): List<TripRecord> = dao.all(vin).map { it.toRecord() }

    override suspend fun count(): Int = dao.count()

    override suspend fun deleteAll() = dao.deleteAll()
}

class RoomRefuelRepository(private val dao: RefuelDao) : RefuelRepository {
    override suspend fun addIfAbsent(refuel: Refuel, vin: String): Boolean =
        dao.insertIfAbsent(refuel.toEntity(vin)) != -1L

    override suspend fun update(refuel: Refuel) = dao.updateKeepingVin(refuel.toEntity(vin = ""))

    override suspend fun byId(id: String): Refuel? = dao.byId(id)?.toDomain()

    override suspend fun pending(vin: String?): List<Refuel> =
        dao.withStatus(RefuelStatus.PENDING, vin).map { it.toDomain() }

    override suspend fun confirmedUpTo(moment: Instant, vin: String?): List<Refuel> =
        dao.withStatusUpTo(RefuelStatus.CONFIRMED, moment, vin).map { it.toDomain() }

    override suspend fun all(vin: String?): List<Refuel> = dao.all(vin).map { it.toDomain() }

    override suspend fun count(): Int = dao.count()

    override suspend fun deleteAll() = dao.deleteAll()
}

class RoomFuelReadingRepository(private val dao: FuelReadingDao) : FuelReadingRepository {
    override suspend fun addIfAbsent(reading: FuelReading, vin: String): Boolean =
        dao.insertIfAbsent(reading.toEntity(vin)) != -1L

    override suspend fun latest(vin: String): FuelReading? = dao.latest(vin)?.toDomain()

    override suspend fun since(from: Instant, vin: String): List<FuelReading> =
        dao.since(from, vin).map { it.toDomain() }

    override suspend fun count(): Int = dao.count()

    override suspend fun deleteAll() = dao.deleteAll()
}

class RoomCostRepository(private val dao: TripCostDao) : CostRepository {
    override suspend fun saveAll(costs: List<TripCost>): Int {
        dao.replaceAll(costs.map { it.toEntity() })
        return costs.size
    }

    override suspend fun forTrip(tripId: String): TripCost? = dao.forTrip(tripId)?.toDomain()

    override suspend fun all(): List<TripCost> = dao.all().map { it.toDomain() }

    override suspend fun count(): Int = dao.count()

    override suspend fun deleteAll() = dao.deleteAll()
}

class RoomVehicleRepository(private val dao: VehicleDao) : VehicleRepository {
    override suspend fun upsert(vehicle: VehicleRecord) = dao.upsertKeepingFirstSeen(vehicle.toEntity())

    override suspend fun byVin(vin: String): VehicleRecord? = dao.byVin(vin)?.toRecord()

    override suspend fun all(): List<VehicleRecord> = dao.all().map { it.toRecord() }

    override suspend fun count(): Int = dao.count()

    override suspend fun deleteAll() = dao.deleteAll()
}

class RoomSyncStateRepository(private val dao: SyncStateDao) : SyncStateRepository {
    override suspend fun get(): SyncState = dao.get()?.toDomain() ?: SyncState()

    override suspend fun save(state: SyncState) = dao.save(state.toEntity())

    override suspend fun deleteAll() = dao.deleteAll()
}
