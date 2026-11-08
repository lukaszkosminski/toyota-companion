package pl.lk.toyotacompanion.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.RefuelStatus

private const val SCHEMA_DIR = "schemas/pl.lk.toyotacompanion.data.db.AppDatabase"
private const val CURRENT_VERSION = 6

/**
 * The archive cannot be fetched from Toyota again once it is older than ~12 months, so every committed schema
 * (`app/schemas/.../N.json`) must still open in the current app with its rows intact (docs/ARCHITECTURE.md failure table).
 *
 * Each test builds the database of version N the way Room 3 would have left it - the `createSql` of every entity and index
 * and the `setupQueries` (room_master_table with the identity hash) of that schema file, `PRAGMA user_version = N` - fills
 * the tables that exist in that version through plain SQL, and opens it with the current [AppDatabase] (the five
 * auto-migrations 1 -> 2 -> 3 -> 4 -> 5 -> 6). Nothing in the app code is touched.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    private lateinit var context: Context
    private val opened = ArrayList<AppDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
    }

    @Test
    fun `a schema file exists for every version up to the current one`() {
        for (version in 1..CURRENT_VERSION) {
            val database = schemaOf(version)
            assertEquals(version, database.getInt("version"))
        }
        assertTrue(!File("$SCHEMA_DIR/${CURRENT_VERSION + 1}.json").exists(), "a new schema version needs a new migration test")
    }

    @Test
    fun `version 1 migrates to the current one and keeps its archive`() = runTest {
        val db = migrate(1)
        assertArchiveKept(db, 1)
        assertNoLaterTables(db, hasWeather = false, hasRoutes = false)
    }

    @Test
    fun `version 2 keeps the weather rows and gives them no weather code`() = runTest {
        val db = migrate(2)
        assertArchiveKept(db, 2)
        assertWeatherKept(db, expectedCode = null)
        assertNoLaterTables(db, hasWeather = true, hasRoutes = false)
    }

    @Test
    fun `version 3 keeps the stored routes`() = runTest {
        val db = migrate(3)
        assertArchiveKept(db, 3)
        assertWeatherKept(db, expectedCode = null)
        assertRouteKept(db)
        assertNoLaterTables(db, hasWeather = true, hasRoutes = true)
    }

    @Test
    fun `version 4 keeps the weather code and gets an empty expenses table`() = runTest {
        val db = migrate(4)
        assertArchiveKept(db, 4)
        assertWeatherKept(db, expectedCode = 61)
        assertRouteKept(db)
        assertNoLaterTables(db, hasWeather = true, hasRoutes = true)
    }

    @Test
    fun `version 5 gets the motorway and speeding columns, empty for the trips it already had`() = runTest {
        val db = migrate(5)
        assertArchiveKept(db, 5)
        assertWeatherKept(db, expectedCode = 61)
        assertRouteKept(db)
        assertEquals(ExpenseEntity("e1", 20_000, "SERVICE", BigDecimal("250.50"), "oil"), db.expenseDao().all().single())
        // A trip written after the migration keeps the new columns.
        db.tripDao().upsertAll(listOf(assertNotNull(db.tripDao().byId("t1")).copy(id = "t3", overspeedLengthM = 10L, highwayDurationS = 20L)))
        val written = assertNotNull(db.tripDao().byId("t3"))
        assertEquals(10L, written.overspeedLengthM)
        assertEquals(20L, written.highwayDurationS)
        assertNull(written.overspeedDurationS)
    }

    @Test
    fun `the current version opens its own schema unchanged`() = runTest {
        val db = migrate(CURRENT_VERSION)
        assertArchiveKept(db, 6)
        assertWeatherKept(db, expectedCode = 61)
        assertRouteKept(db)
        val expense = db.expenseDao().all().single()
        assertEquals(ExpenseEntity("e1", 20_000, "SERVICE", BigDecimal("250.50"), "oil"), expense)
    }

    // ---------------- building the old database ----------------

    private fun schemaOf(version: Int): JSONObject =
        JSONObject(File("$SCHEMA_DIR/$version.json").readText()).getJSONObject("database")

    /** Builds the file of [version], seeds it, opens it with the current database and returns that. */
    private fun migrate(version: Int): AppDatabase {
        val name = "migration-v$version.db"
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = schemaOf(version)
            val entities = schema.getJSONArray("entities")
            val tables = HashSet<String>()
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                tables += table
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            seed(old, version, tables)
            old.version = version
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setDriver(AndroidSQLiteDriver())
            .build()
        opened += db
        return db
    }

    /** Rows of every table that exists in [version]; the weather code column only from 4 on. */
    private fun seed(old: SQLiteDatabase, version: Int, tables: Set<String>) {
        old.execSQL(
            "INSERT INTO trips (id, vin, start_ts, end_ts, distance_km, fuel_l, ev_distance_km, start_lat, start_lon, end_lat, end_lon, " +
                "duration_idle_s, average_speed, max_speed, night_trip, score_global, score_acceleration, score_braking, score_advice, " +
                "score_constant_speed, hdc_ev_distance_m, hdc_ev_time_s, hdc_eco_dist_m, hdc_eco_time_s, hdc_power_dist_m, hdc_power_time_s, " +
                "hdc_charge_dist_m, hdc_charge_time_s, synced_at) VALUES " +
                "('t1', 'VIN1', 1000, 2000, 12.5, '0.600', 3.5, 54.372, 18.638, 54.401, 18.601, " +
                "120, 31.5, 88.0, 1, 85, 70, 60, 50, 40, 1000, 100, 2000, 200, 3000, 300, 4000, 400, 5000)",
        )
        old.execSQL(
            "INSERT INTO trips (id, vin, start_ts, end_ts, distance_km, fuel_l) VALUES ('t2', 'VIN1', 3000, 4000, 0.0, '0')",
        )
        if (version >= 6) {
            old.execSQL(
                "UPDATE trips SET overspeed_length_m = 1334, overspeed_duration_s = 57, highway_length_m = 4210, highway_duration_s = 190 " +
                    "WHERE id = 't1'",
            )
        }
        old.execSQL(
            "INSERT INTO refuels (id, vin, ts, odometer_km, level_before, level_after, liters, price_per_liter, total_cost, status, " +
                "liters_source, filled_to_full) VALUES ('r1', 'VIN1', 1500, 12345.6, 20, 80, '30.10', '6.49', '195.35', 'CONFIRMED', 'RECEIPT', 1)",
        )
        old.execSQL(
            "INSERT INTO refuels (id, vin, ts, odometer_km, level_before, level_after, status) " +
                "VALUES ('r2', 'VIN1', 2500, 12400.0, 10, 70, 'PENDING')",
        )
        old.execSQL(
            "INSERT INTO fuel_readings (vin, ts, level_pct, odometer_km, battery_level_pct, distance_to_empty_km) " +
                "VALUES ('VIN1', 1200, 55, 12340.0, 40, 480.5)",
        )
        old.execSQL(
            "INSERT INTO trip_costs (trip_id, cost_pln, price_per_liter, refuel_id, source) VALUES ('t1', '3.89', '6.49', 'r1', 'REFUEL')",
        )
        old.execSQL(
            "INSERT INTO vehicles (vin, brand, model_name, fuel_type, ev_vehicle, first_seen, last_seen) " +
                "VALUES ('VIN1', 'T', 'Yaris', 'hybrid', 0, 100, 200)",
        )
        old.execSQL(
            "INSERT INTO sync_state (id, vin, watermark_epoch_day, last_attempt_at, last_success_at, last_error_at, last_error_kind, " +
                "last_error_message) VALUES (1, 'VIN1', 20000, 900, 800, 700, 'NETWORK', 'no network')",
        )
        if ("weather" in tables) {
            if (version >= 4) {
                old.execSQL(
                    "INSERT INTO weather (lat_r, lon_r, date, temp_mean, temp_min, temp_max, precip_mm, precip_h, wind_max, fetched_at, " +
                        "weather_code) VALUES ('54.37', '18.63', '2026-10-30', 8.5, 4.0, 12.0, 1.2, 3.0, 20.0, 6000, 61)",
                )
            } else {
                old.execSQL(
                    "INSERT INTO weather (lat_r, lon_r, date, temp_mean, temp_min, temp_max, precip_mm, precip_h, wind_max, fetched_at) " +
                        "VALUES ('54.37', '18.63', '2026-10-30', 8.5, 4.0, 12.0, 1.2, 3.0, 20.0, 6000)",
                )
            }
        }
        if ("trip_routes" in tables) {
            old.execSQL("INSERT INTO trip_routes (trip_id, points, fetched_at) VALUES ('t1', 'encoded-route', 7000)")
        }
        if ("expenses" in tables) {
            old.execSQL(
                "INSERT INTO expenses (id, date_epoch_day, category, amount_pln, note) VALUES ('e1', 20000, 'SERVICE', '250.50', 'oil')",
            )
        }
    }

    // ---------------- checks after the migration ----------------

    /** The tables that exist since version 1, with the exact values written above. */
    private suspend fun assertArchiveKept(db: AppDatabase, version: Int) {
        val trip = assertNotNull(db.tripDao().byId("t1"))
        assertEquals("VIN1", trip.vin)
        assertEquals(Instant.ofEpochMilli(1000), trip.start)
        assertEquals(Instant.ofEpochMilli(2000), trip.end)
        assertEquals(12.5, trip.distanceKm)
        assertEquals("0.600", trip.fuelL.toPlainString())
        assertEquals(3.5, trip.evDistanceKm)
        assertEquals(54.372, trip.startLat)
        assertEquals(18.601, trip.endLon)
        assertEquals(120L, trip.durationIdleS)
        assertEquals(true, trip.nightTrip)
        assertEquals(85, trip.scoreGlobal)
        assertEquals(40, trip.scoreConstantSpeed)
        assertEquals(2000L, trip.hdcEcoDistM)
        assertEquals(400L, trip.hdcChargeTimeS)
        assertEquals(Instant.ofEpochMilli(5000), trip.syncedAt)
        // Schema 6 added the motorway and speeding columns: a trip of an older file has "not reported", never 0.
        if (version >= 6) {
            assertEquals(1_334L, trip.overspeedLengthM)
            assertEquals(57L, trip.overspeedDurationS)
            assertEquals(4_210L, trip.highwayLengthM)
            assertEquals(190L, trip.highwayDurationS)
        } else {
            assertNull(trip.overspeedLengthM)
            assertNull(trip.overspeedDurationS)
            assertNull(trip.highwayLengthM)
            assertNull(trip.highwayDurationS)
        }
        val bare = assertNotNull(db.tripDao().byId("t2"))
        assertNull(bare.overspeedLengthM)
        assertNull(bare.evDistanceKm)
        assertNull(bare.syncedAt)
        assertEquals(2, db.tripDao().count())
        assertEquals(listOf("t1", "t2"), db.tripDao().all("VIN1").map { it.id })

        val refuel = assertNotNull(db.refuelDao().byId("r1"))
        assertEquals(RefuelStatus.CONFIRMED, refuel.status)
        assertEquals(LitersSource.RECEIPT, refuel.litersSource)
        assertEquals(true, refuel.filledToFull)
        assertEquals("30.10", refuel.liters?.toPlainString())
        assertEquals("195.35", refuel.totalCost?.toPlainString())
        assertEquals(12345.6, refuel.odometerKm)
        val pending = assertNotNull(db.refuelDao().byId("r2"))
        assertEquals(RefuelStatus.PENDING, pending.status)
        assertNull(pending.liters)
        assertNull(pending.filledToFull)
        assertEquals(listOf("r2"), db.refuelDao().withStatus(RefuelStatus.PENDING, "VIN1").map { it.id })

        val reading = assertNotNull(db.fuelReadingDao().latest("VIN1"))
        assertEquals(Instant.ofEpochMilli(1200), reading.ts)
        assertEquals(55, reading.levelPct)
        assertEquals(40, reading.batteryLevelPct)
        assertEquals(480.5, reading.distanceToEmptyKm)

        val cost = assertNotNull(db.tripCostDao().forTrip("t1"))
        assertEquals("3.89", cost.costPln?.toPlainString())
        assertEquals("r1", cost.refuelId)
        assertEquals(CostSource.REFUEL, cost.source)

        val vehicle = assertNotNull(db.vehicleDao().byVin("VIN1"))
        assertEquals("Yaris", vehicle.modelName)
        assertEquals(false, vehicle.evVehicle)
        assertEquals(Instant.ofEpochMilli(100), vehicle.firstSeen)

        val state = assertNotNull(db.syncStateDao().get())
        assertEquals("VIN1", state.vin)
        assertEquals(20000L, state.watermarkEpochDay)
        assertEquals(pl.lk.toyotacompanion.core.data.SyncErrorKind.NETWORK, state.lastErrorKind)
        assertEquals("no network", state.lastErrorMessage)
    }

    private suspend fun assertWeatherKept(db: AppDatabase, expectedCode: Int?) {
        val row = db.weatherDao().all().single()
        assertEquals("54.37", row.latitude)
        assertEquals("2026-10-30", row.date)
        assertEquals(8.5, row.tempMeanC)
        assertEquals(1.2, row.precipitationMm)
        assertEquals(20.0, row.windMaxKmh)
        assertEquals(Instant.ofEpochMilli(6000), row.fetchedAt)
        assertEquals(expectedCode, row.weatherCode)
    }

    private suspend fun assertRouteKept(db: AppDatabase) {
        val route = assertNotNull(db.tripRouteDao().byTrip("t1"))
        assertEquals("encoded-route", route.points)
        assertEquals(Instant.ofEpochMilli(7000), route.fetchedAt)
        assertEquals(listOf("t1"), db.tripRouteDao().ids())
    }

    /** Tables the old version did not have exist, are empty and accept a write; the old ones are not touched. */
    private suspend fun assertNoLaterTables(db: AppDatabase, hasWeather: Boolean, hasRoutes: Boolean) {
        if (!hasWeather) assertEquals(0, db.weatherDao().count())
        if (!hasRoutes) assertEquals(0, db.tripRouteDao().count())
        if (!hasWeather) {
            db.weatherDao().replaceAll(
                listOf(WeatherEntity("1.00", "2.00", "2026-01-01", 1.0, null, null, null, null, null, Instant.ofEpochMilli(1))),
            )
            assertEquals(1, db.weatherDao().count())
            assertNull(db.weatherDao().all().single().weatherCode)
        }
        if (!hasRoutes) {
            db.tripRouteDao().save(TripRouteEntity("t2", "", Instant.ofEpochMilli(2)))
            assertEquals(listOf("t2"), db.tripRouteDao().ids())
        }
        assertEquals(emptyList(), db.expenseDao().all())
        val expense = ExpenseEntity("e9", 20_001, "OTHER", BigDecimal("1.00"), null)
        db.expenseDao().upsert(expense)
        assertEquals(listOf(expense), db.expenseDao().all())
    }
}
