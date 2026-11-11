package pl.lk.toyotacompanion.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import java.time.Instant
import pl.lk.toyotacompanion.core.domain.RefuelStatus

@Dao
abstract class TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replace(entity: TripEntity)

    @Query("SELECT COUNT(*) FROM trips WHERE id = :id")
    protected abstract suspend fun countById(id: String): Int

    /** Sequential check-then-write in one transaction; returns the number of ids that were new. */
    @Transaction
    open suspend fun upsertAll(entities: List<TripEntity>): Int {
        var added = 0
        for (entity in entities) {
            if (countById(entity.id) == 0) added++
            replace(entity)
        }
        return added
    }

    @Query("SELECT * FROM trips WHERE id = :id")
    abstract suspend fun byId(id: String): TripEntity?

    @Query("SELECT id FROM trips WHERE vin = :vin AND start_ts >= :from AND start_ts <= :to")
    abstract suspend fun idsBetween(vin: String, from: Instant, to: Instant): List<String>

    @Query("SELECT * FROM trips WHERE vin = :vin AND start_ts >= :start ORDER BY start_ts DESC, id")
    abstract suspend fun allSince(vin: String, start: Instant): List<TripEntity>

    @Query("SELECT * FROM trips WHERE vin = :vin ORDER BY start_ts, id")
    abstract suspend fun all(vin: String): List<TripEntity>

    @Query("SELECT COUNT(*) FROM trips")
    abstract suspend fun count(): Int

    @Query("DELETE FROM trips")
    abstract suspend fun deleteAll()
}

@Dao
abstract class RefuelDao {
    /** Returns the new rowid, or -1 when the id already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(entity: RefuelEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replace(entity: RefuelEntity)

    @Query("SELECT * FROM refuels WHERE id = :id")
    abstract suspend fun byId(id: String): RefuelEntity?

    /** Replaces the row but keeps the stored VIN. */
    @Transaction
    open suspend fun updateKeepingVin(entity: RefuelEntity) {
        val existing = byId(entity.id) ?: return
        // The photo is not part of the correction (PRD F59): the row keeps its link.
        replace(entity.copy(vin = existing.vin, receiptFile = existing.receiptFile))
    }

    @Query("UPDATE refuels SET receipt_file = :name WHERE id = :id")
    abstract suspend fun setReceipt(id: String, name: String?): Int

    @Query("SELECT receipt_file FROM refuels WHERE id = :id")
    abstract suspend fun receiptOf(id: String): String?

    @Query("SELECT id, receipt_file AS receiptFile FROM refuels WHERE receipt_file IS NOT NULL")
    abstract suspend fun receiptRefs(): List<ReceiptRef>

    @Query("SELECT receipt_file FROM refuels WHERE receipt_file IS NOT NULL")
    abstract suspend fun receiptNames(): List<String>

    @Query("UPDATE refuels SET receipt_file = NULL WHERE receipt_file = :name")
    abstract suspend fun clearReceipt(name: String)

    @Query(
        "SELECT * FROM refuels WHERE status = :status AND (:vin IS NULL OR vin = :vin) ORDER BY ts DESC, id",
    )
    abstract suspend fun withStatus(status: RefuelStatus, vin: String?): List<RefuelEntity>

    @Query(
        "SELECT * FROM refuels WHERE status = :status AND ts <= :moment AND (:vin IS NULL OR vin = :vin) ORDER BY ts, id",
    )
    abstract suspend fun withStatusUpTo(
        status: RefuelStatus,
        moment: Instant,
        vin: String?,
    ): List<RefuelEntity>

    @Query("SELECT * FROM refuels WHERE (:vin IS NULL OR vin = :vin) ORDER BY ts, id")
    abstract suspend fun all(vin: String?): List<RefuelEntity>

    @Query("SELECT COUNT(*) FROM refuels")
    abstract suspend fun count(): Int

    @Query("DELETE FROM refuels")
    abstract suspend fun deleteAll()
}

@Dao
interface FuelReadingDao {
    /** Returns the new rowid, or -1 when `(vin, ts)` already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: FuelReadingEntity): Long

    @Query("SELECT * FROM fuel_readings WHERE vin = :vin ORDER BY ts DESC LIMIT 1")
    suspend fun latest(vin: String): FuelReadingEntity?

    @Query("SELECT * FROM fuel_readings WHERE vin = :vin AND ts >= :from ORDER BY ts")
    suspend fun since(from: Instant, vin: String): List<FuelReadingEntity>

    @Query("SELECT COUNT(*) FROM fuel_readings")
    suspend fun count(): Int

    @Query("DELETE FROM fuel_readings")
    suspend fun deleteAll()
}

@Dao
abstract class TripCostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun replaceAll(entities: List<TripCostEntity>)

    @Query("SELECT * FROM trip_costs WHERE trip_id = :tripId")
    abstract suspend fun forTrip(tripId: String): TripCostEntity?

    @Query("SELECT * FROM trip_costs ORDER BY trip_id")
    abstract suspend fun all(): List<TripCostEntity>

    @Query("SELECT COUNT(*) FROM trip_costs")
    abstract suspend fun count(): Int

    @Query("DELETE FROM trip_costs")
    abstract suspend fun deleteAll()
}

@Dao
abstract class VehicleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replace(entity: VehicleEntity)

    @Query("SELECT * FROM vehicles WHERE vin = :vin")
    abstract suspend fun byVin(vin: String): VehicleEntity?

    /** Keeps the stored `firstSeen`. */
    @Transaction
    open suspend fun upsertKeepingFirstSeen(entity: VehicleEntity) {
        val previous = byVin(entity.vin)
        replace(if (previous == null) entity else entity.copy(firstSeen = previous.firstSeen))
    }

    @Query("SELECT * FROM vehicles ORDER BY vin")
    abstract suspend fun all(): List<VehicleEntity>

    @Query("SELECT COUNT(*) FROM vehicles")
    abstract suspend fun count(): Int

    @Query("DELETE FROM vehicles")
    abstract suspend fun deleteAll()
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = $SYNC_STATE_ID")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun deleteAll()
}

@Dao
interface WeatherDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAll(entities: List<WeatherEntity>)

    @Query("SELECT * FROM weather ORDER BY date, lat_r, lon_r")
    suspend fun all(): List<WeatherEntity>

    /** `date` is ISO text, so the string order is the date order. */
    @Query("DELETE FROM weather WHERE date < :date")
    suspend fun pruneBefore(date: String): Int

    @Query("SELECT COUNT(*) FROM weather")
    suspend fun count(): Int

    @Query("DELETE FROM weather")
    suspend fun deleteAll()
}

@Dao
interface TripRouteDao {
    @Query("SELECT * FROM trip_routes WHERE trip_id = :tripId")
    suspend fun byTrip(tripId: String): TripRouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: TripRouteEntity)

    @Query("SELECT trip_id FROM trip_routes")
    suspend fun ids(): List<String>

    @Query("SELECT COUNT(*) FROM trip_routes")
    suspend fun count(): Int

    @Query("DELETE FROM trip_routes")
    suspend fun deleteAll()
}

@Dao
abstract class ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAllIfAbsent(entities: List<ExpenseEntity>)

    @Query("SELECT * FROM expenses ORDER BY date_epoch_day DESC, id")
    abstract suspend fun all(): List<ExpenseEntity>

    @Query("DELETE FROM expenses WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("UPDATE expenses SET receipt_file = :name WHERE id = :id")
    abstract suspend fun setReceipt(id: String, name: String?): Int

    @Query("SELECT receipt_file FROM expenses WHERE id = :id")
    abstract suspend fun receiptOf(id: String): String?

    @Query("SELECT id, receipt_file AS receiptFile FROM expenses WHERE receipt_file IS NOT NULL")
    abstract suspend fun receiptRefs(): List<ReceiptRef>

    @Query("SELECT receipt_file FROM expenses WHERE receipt_file IS NOT NULL")
    abstract suspend fun receiptNames(): List<String>

    @Query("UPDATE expenses SET receipt_file = NULL WHERE receipt_file = :name")
    abstract suspend fun clearReceipt(name: String)
}

/** An entry id with the name of its receipt photo (PRD F59). */
data class ReceiptRef(val id: String, val receiptFile: String)
