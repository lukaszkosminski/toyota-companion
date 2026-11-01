package pl.lk.toyotacompanion.data.db

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.ColumnTypeConverters

/**
 * The on-phone archive (docs/SPEC.md §8.2). Schema JSON is exported to `app/schemas` and committed.
 * No destructive migration: the archive outlives Toyota's ~12 month retention and cannot be re-fetched,
 * so every schema change needs an explicit migration.
 */
@Database(
    entities = [
        TripEntity::class,
        RefuelEntity::class,
        FuelReadingEntity::class,
        TripCostEntity::class,
        VehicleEntity::class,
        SyncStateEntity::class,
        WeatherEntity::class,
    ],
    version = 2,
    // 1 -> 2 only adds the `weather` table (docs/SPEC.md §23); the archive tables are untouched.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    abstract fun refuelDao(): RefuelDao

    abstract fun fuelReadingDao(): FuelReadingDao

    abstract fun tripCostDao(): TripCostDao

    abstract fun vehicleDao(): VehicleDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun weatherDao(): WeatherDao
}
