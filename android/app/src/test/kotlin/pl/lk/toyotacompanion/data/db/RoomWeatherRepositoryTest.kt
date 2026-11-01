package pl.lk.toyotacompanion.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.core.data.WeatherRow
import pl.lk.toyotacompanion.core.weather.WeatherDay

private val FETCHED: Instant = Instant.parse("2026-11-01T10:00:00Z")

private fun row(lat: String, lon: String, date: String, mean: Double? = 5.0, fetched: Instant = FETCHED) =
    WeatherRow(lat, lon, WeatherDay(LocalDate.parse(date), mean, mean?.minus(2), mean?.plus(2), 0.0, 0.0, 12.0), fetched)

/** docs/SPEC.md §23: the weather cache on an in-memory Room database, and the 1 -> 2 migration. */
@RunWith(RobolectricTestRunner::class)
class RoomWeatherRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomWeatherRepository

    @Before
    fun open() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
        repository = RoomWeatherRepository(db.weatherDao())
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `a saved row reads back with its null values`() = runTest {
        repository.save(listOf(row("54.37", "18.63", "2026-10-30"), row("54.37", "18.63", "2026-10-31", mean = null)))
        val rows = repository.all()
        assertEquals(2, rows.size)
        assertEquals(row("54.37", "18.63", "2026-10-30"), rows[0])
        assertNull(rows[1].day.tempMeanC)
        assertEquals(12.0, rows[1].day.windMaxKmh)
    }

    @Test
    fun `the weather code is kept`() = runTest {
        val base = row("54.37", "18.63", "2026-10-30")
        repository.save(listOf(base.copy(day = base.day.copy(weatherCode = 61))))
        assertEquals(61, repository.all().single().day.weatherCode)
    }

    @Test
    fun `the same place and day is one row and the later save wins`() = runTest {
        repository.save(listOf(row("54.37", "18.63", "2026-10-30", mean = null)))
        repository.save(listOf(row("54.37", "18.63", "2026-10-30", mean = 7.0, fetched = FETCHED.plusSeconds(60))))
        val rows = repository.all()
        assertEquals(1, rows.size)
        assertEquals(7.0, rows.single().day.tempMeanC)
        assertEquals(FETCHED.plusSeconds(60), rows.single().fetchedAt)
    }

    @Test
    fun `places and days are separate rows, oldest day first`() = runTest {
        repository.save(listOf(row("54.37", "18.63", "2026-10-31"), row("54.40", "18.63", "2026-10-30"), row("54.37", "18.63", "2026-10-30")))
        assertEquals(3, repository.count())
        assertEquals(listOf("2026-10-30", "2026-10-30", "2026-10-31"), repository.all().map { it.day.date.toString() })
    }

    @Test
    fun `prune drops only days before the date`() = runTest {
        repository.save(listOf(row("54.37", "18.63", "2026-01-05"), row("54.37", "18.63", "2026-10-30")))
        assertEquals(1, repository.pruneBefore(LocalDate.parse("2026-06-01")))
        assertEquals(listOf("2026-10-30"), repository.all().map { it.day.date.toString() })
        repository.deleteAll()
        assertEquals(0, repository.count())
    }

    /** A database made from the committed schema 1 keeps its archive when Room migrates it to 2. */
    @Test
    fun `migration 1 to 2 adds the weather table and keeps the trips`() = runTest {
        db.close()
        val schema = JSONObject(File("schemas/pl.lk.toyotacompanion.data.db.AppDatabase/1.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath("migration.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL(
                "INSERT INTO trips (id, vin, start_ts, end_ts, distance_km, fuel_l) VALUES ('t1', 'VIN', 1, 2, 10.0, '0.5')",
            )
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, "migration.db")
            .setDriver(AndroidSQLiteDriver())
            .build()
        try {
            assertEquals(1, migrated.tripDao().count())
            RoomWeatherRepository(migrated.weatherDao()).apply {
                save(listOf(row("54.37", "18.63", "2026-10-30")))
                assertEquals(1, count())
            }
        } finally {
            migrated.close()
        }
    }
}

/** docs/SPEC.md §27: the stored routes of trips on an in-memory Room database. */
@RunWith(RobolectricTestRunner::class)
class RoomTripRouteRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomTripRouteRepository

    @Before
    fun open() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
        repository = RoomTripRouteRepository(db.tripRouteDao())
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `a saved route reads back in order with its battery marks`() = runTest {
        val points = listOf(
            pl.lk.toyotacompanion.core.routes.TripRoutePoint(54.37201, 18.63801, true),
            pl.lk.toyotacompanion.core.routes.TripRoutePoint(54.37301, 18.63901, false),
            pl.lk.toyotacompanion.core.routes.TripRoutePoint(54.37401, 18.64001, null),
        )
        repository.save("t1", points)
        assertEquals(points, repository.get("t1"))
        assertNull(repository.get("other"))
    }

    @Test
    fun `an empty route is stored as empty, not as unknown, and a save replaces`() = runTest {
        repository.save("t1", emptyList())
        assertEquals(emptyList(), repository.get("t1"))
        repository.save("t1", listOf(pl.lk.toyotacompanion.core.routes.TripRoutePoint(1.0, 2.0, true)))
        assertEquals(1, repository.get("t1")?.size)
        assertEquals(1, repository.count())
        repository.deleteAll()
        assertEquals(0, repository.count())
    }
}
