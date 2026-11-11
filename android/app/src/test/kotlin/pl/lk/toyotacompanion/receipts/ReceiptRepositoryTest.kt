package pl.lk.toyotacompanion.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.data.db.AppDatabase
import pl.lk.toyotacompanion.data.db.ExpenseEntity
import pl.lk.toyotacompanion.data.db.RefuelEntity

/** PRD F59, docs/SPEC.md §59: the photo links of the rows and the files they point to, against a database in memory. */
@RunWith(RobolectricTestRunner::class)
// Real decoding and encoding: the default graphics shadow accepts any bytes as a picture, so the checks below would prove nothing.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var folder: File
    private lateinit var store: ReceiptStore
    private lateinit var repository: ReceiptRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
        folder = File(tmp.root, "receipts")
        store = ReceiptStore(folder, context.contentResolver)
        repository = ReceiptRepository(store, db.refuelDao(), db.expenseDao(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun photo(name: String): Uri {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val file = File(tmp.root, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun refuel(id: String) = RefuelEntity(
        id = id,
        vin = "VIN1",
        ts = Instant.parse("2026-10-01T08:00:00Z"),
        odometerKm = 41_230.0,
        levelBefore = 12,
        levelAfter = 96,
        liters = BigDecimal("41.230"),
        pricePerLiter = BigDecimal("6.190"),
        totalCost = BigDecimal("255.21"),
        status = RefuelStatus.CONFIRMED,
        litersSource = null,
        filledToFull = true,
    )

    private suspend fun expense(id: String) = db.expenseDao().upsert(ExpenseEntity(id, 20_000, "SERVICE", BigDecimal("250.50"), null))

    @Test
    fun `attaching a photo links the row, replacing it removes the old file, removing it clears the link`() = runTest {
        db.refuelDao().insertIfAbsent(refuel("r1"))

        assertTrue(repository.attach(ReceiptOwner.REFUEL, "r1", photo("first.jpg")))
        val first = assertNotNull(db.refuelDao().receiptOf("r1"))
        assertTrue(assertNotNull(store.file(first)).isFile)
        assertEquals(setOf(receiptKey(ReceiptOwner.REFUEL, "r1")), repository.attached.value)
        assertEquals(first, repository.nameOf(ReceiptOwner.REFUEL, "r1"))

        assertTrue(repository.attach(ReceiptOwner.REFUEL, "r1", photo("second.jpg")))
        val second = assertNotNull(db.refuelDao().receiptOf("r1"))
        assertFalse(assertNotNull(store.file(first)).exists(), "the replaced photo is gone")
        assertTrue(assertNotNull(store.file(second)).isFile)

        repository.remove(ReceiptOwner.REFUEL, "r1")
        assertNull(db.refuelDao().receiptOf("r1"))
        assertFalse(assertNotNull(store.file(second)).exists())
        assertTrue(repository.attached.value.isEmpty())
    }

    @Test
    fun `a photo for a missing row is not kept`() = runTest {
        assertFalse(repository.attach(ReceiptOwner.EXPENSE, "missing", photo("orphan.jpg")))
        assertTrue(store.files().isEmpty())
    }

    @Test
    fun `a photo that cannot be read leaves the entry and its photo as they were`() = runTest {
        db.refuelDao().insertIfAbsent(refuel("r1"))
        repository.attach(ReceiptOwner.REFUEL, "r1", photo("good.jpg"))
        val before = assertNotNull(db.refuelDao().receiptOf("r1"))

        val broken = File(tmp.root, "broken.jpg").apply { writeText("not a picture") }
        assertFailsWith<ReceiptException> { repository.attach(ReceiptOwner.REFUEL, "r1", Uri.fromFile(broken)) }

        assertEquals(before, db.refuelDao().receiptOf("r1"))
        assertTrue(assertNotNull(store.file(before)).isFile)
        assertEquals(1, store.files().size)
    }

    @Test
    fun `deleting an expense deletes its photo with it`() = runTest {
        expense("e1")
        repository.attach(ReceiptOwner.EXPENSE, "e1", photo("expense.jpg"))
        val name = assertNotNull(db.expenseDao().receiptOf("e1"))

        repository.deleteExpense("e1")

        assertNull(db.expenseDao().receiptOf("e1"))
        assertFalse(assertNotNull(store.file(name)).exists())
        assertTrue(repository.attached.value.isEmpty())
    }

    @Test
    fun `a discarded import leaves no file behind`() = runTest {
        val name = repository.importPending(photo("pending.jpg"))
        assertTrue(assertNotNull(store.file(name)).isFile)
        repository.discard(name)
        assertFalse(assertNotNull(store.file(name)).exists())
    }

    @Test
    fun `the start-up sweep clears links to missing photos, deletes unlinked files and interrupted temporary files`() = runTest {
        db.refuelDao().insertIfAbsent(refuel("r1"))
        db.expenseDao().upsert(ExpenseEntity("e1", 20_000, "SERVICE", BigDecimal("1.00"), null, "9f8e7d6c-1111-4222-8333-000011112222.jpg"))
        repository.attach(ReceiptOwner.REFUEL, "r1", photo("kept.jpg"))
        val kept = assertNotNull(db.refuelDao().receiptOf("r1"))
        val leftover = repository.importPending(photo("leftover.jpg"))
        File(folder, "tmp-interrupted").writeText("partial")

        repository.sweep()

        // The link to a photo that is not on the phone (a restore from another phone) is cleared; the entry stays.
        assertNull(db.expenseDao().receiptOf("e1"))
        assertNotNull(db.expenseDao().all().singleOrNull { it.id == "e1" })
        // A photo with a link stays, a photo with no link is deleted, a temporary file is deleted.
        assertTrue(assertNotNull(store.file(kept)).isFile)
        assertFalse(assertNotNull(store.file(leftover)).exists())
        assertFalse(File(folder, "tmp-interrupted").exists())
        assertEquals(setOf(receiptKey(ReceiptOwner.REFUEL, "r1")), repository.attached.value)
        assertFalse(store.files().any { it.startsWith("tmp-") })
    }
}
