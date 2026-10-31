@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.data.ArchiveDecode
import pl.lk.toyotacompanion.core.data.ArchiveReading
import pl.lk.toyotacompanion.core.data.ArchiveRefuel
import pl.lk.toyotacompanion.core.data.ArchiveSnapshot
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.data.decodeArchive
import pl.lk.toyotacompanion.core.data.encodeArchive
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.sync.ArchiveImport
import pl.lk.toyotacompanion.core.sync.ArchiveImportCounts

/**
 * The backup screen controller (PRD F7, F8, docs/SPEC.md §14.2). Files are addressed by an
 * opaque text, so nothing here needs Robolectric or WorkManager: the application scope and
 * the injected io dispatcher are enough.
 */
private val EXPORTED_AT: Instant = Instant.parse("2026-10-05T06:00:00Z")

private val OLD_EXPORT: Instant = Instant.parse("2026-09-01T06:00:00Z")

private const val TARGET = "content://downloads/archive.json"

private fun snapshotOf(trips: Int = 2): ArchiveSnapshot = ArchiveSnapshot(
    exportedAt = EXPORTED_AT,
    vehicles = listOf(VehicleRecord("VIN1", "TOYOTA", "Corolla", "HEV", false, EXPORTED_AT, EXPORTED_AT)),
    trips = (1..trips).map {
        TripRecord(
            trip = Trip("t$it", EXPORTED_AT.minusSeconds(3_600), EXPORTED_AT, 10.0, BigDecimal("0.500"), null),
            vin = "VIN1",
        )
    },
    refuels = listOf(
        ArchiveRefuel(
            "VIN1",
            Refuel("r1", EXPORTED_AT.minusSeconds(7_200), 100.0, 20, 80, BigDecimal("41.230"), BigDecimal("6.190"), null, RefuelStatus.CONFIRMED, LitersSource.RECEIPT, true),
        ),
    ),
    readings = listOf(ArchiveReading("VIN1", FuelReading(EXPORTED_AT.minusSeconds(60), 62, 100.0, null, null))),
)

private val COUNTS = ArchiveImportCounts(
    vehiclesAdded = 1,
    tripsAdded = 3,
    refuelsAdded = 2,
    refuelsUpdated = 1,
    refuelsSkipped = 1,
    readingsAdded = 4,
    otherVehicleTrips = 2,
    tripsCosted = 3,
)

/** The engine, the file and the settings file behind the controller: counts calls, throws what the test asks. */
private class FakeBackup {
    var snapshot: ArchiveSnapshot = snapshotOf()
    var exportError: Exception? = null
    var exportCalls = 0

    var importResult: ArchiveImport = ArchiveImport.Done(COUNTS)
    var importError: Exception? = null
    val imported = mutableListOf<ArchiveSnapshot>()

    var writeGate: CompletableDeferred<Unit>? = null
    var writeError: Exception? = null
    var writeCalls = 0
    var writeTarget: String? = null
    var written: ByteArray? = null

    var fileBytes: ByteArray = ByteArray(0)
    var readError: Exception? = null
    var readCalls = 0

    var lastExport: Instant? = null
    var readDateCalls = 0
    var readDateError: Exception? = null
    val savedDates = mutableListOf<Instant>()

    suspend fun export(): ArchiveSnapshot {
        exportCalls++
        exportError?.let { throw it }
        return snapshot
    }

    suspend fun import(snapshot: ArchiveSnapshot): ArchiveImport {
        imported += snapshot
        importError?.let { throw it }
        return importResult
    }

    suspend fun write(target: String, bytes: ByteArray) {
        writeGate?.await()
        writeCalls++
        writeTarget = target
        written = bytes
        writeError?.let { throw it }
    }

    suspend fun read(source: String): ByteArray {
        readCalls++
        readError?.let { throw it }
        return fileBytes
    }

    suspend fun readDate(): Instant? {
        readDateCalls++
        readDateError?.let { throw it }
        return lastExport
    }

    suspend fun saveDate(at: Instant) {
        savedDates += at
        lastExport = at
    }
}

/**
 * docs/SPEC.md §14.2: the buttons, the date, the Polish result and what a late result of
 * an operation that outlived a logout may still change.
 */
class BackupControllerTest {
    private fun controller(backup: FakeBackup, io: CoroutineDispatcher) = BackupController(
        export = { backup.export() },
        import = { backup.import(it) },
        writeFile = { target, bytes -> backup.write(target, bytes) },
        readFile = { backup.read(it) },
        readLastExport = { backup.readDate() },
        saveLastExport = { backup.saveDate(it) },
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
    )

    @Test
    fun `export writes the file and only then remembers the date`() = runTest {
        val backup = FakeBackup()
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.exportTo(TARGET)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(1, backup.exportCalls)
        assertEquals(TARGET, backup.writeTarget)
        val written = assertIs<ArchiveDecode.Ok>(decodeArchive(requireNotNull(backup.written))).snapshot
        assertEquals(snapshotOf(), written, "the bytes handed to the file are the archive itself")
        assertEquals(listOf(EXPORTED_AT), backup.savedDates)
        assertEquals(Texts.backupExported(2), state.message)
        assertEquals(EXPORTED_AT, state.lastExport)
        assertNull(state.error)
        assertTrue(state.loaded)
        assertFalse(state.busy)
    }

    @Test
    fun `a write that fails keeps the old date and shows no file name`() = runTest {
        val backup = FakeBackup().apply {
            lastExport = OLD_EXPORT
            writeError = IllegalStateException("cannot write content://downloads/secret-name.json")
        }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.ensureLoaded()
        advanceUntilIdle()
        assertEquals(OLD_EXPORT, controller.state.value.lastExport)

        controller.exportTo(TARGET)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(Texts.BACKUP_FAILED, state.error)
        assertTrue("secret-name" !in requireNotNull(state.error), "the exception text must not reach the screen: $state")
        assertTrue(backup.savedDates.isEmpty(), "a file that was not written is not a backup")
        assertEquals(OLD_EXPORT, state.lastExport, "the old date stays on screen")
        assertNull(state.message)
        assertFalse(state.busy)
    }

    @Test
    fun `import reports what the file added`() = runTest {
        val backup = FakeBackup().apply { fileBytes = encodeArchive(snapshotOf(trips = 1)) }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.importFrom(TARGET)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(1, backup.readCalls)
        assertEquals(snapshotOf(trips = 1), backup.imported.single())
        assertEquals(Texts.backupImported(COUNTS), state.message)
        assertNull(state.error)
        assertFalse(state.busy)
    }

    @Test
    fun `bytes that are not an archive never reach the engine`() = runTest {
        val backup = FakeBackup().apply { fileBytes = "to nie jest plik".toByteArray(Charsets.UTF_8) }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.importFrom(TARGET)
        advanceUntilIdle()

        assertEquals(Texts.BACKUP_CORRUPT, controller.state.value.error)
        assertTrue(backup.imported.isEmpty(), "nothing may be imported from a file that was not decoded")
        assertFalse(controller.state.value.busy)
    }

    @Test
    fun `a file of another format is refused`() = runTest {
        val foreign = """
            {"format":"toyota-companion-sync","version":1,"exportedAt":"2026-10-05T06:00:00Z",
             "vehicles":[],"trips":[],"refuels":[],"readings":[]}
        """.trimIndent()
        val backup = FakeBackup().apply { fileBytes = foreign.toByteArray(Charsets.UTF_8) }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.importFrom(TARGET)
        advanceUntilIdle()

        assertEquals(Texts.BACKUP_UNSUPPORTED, controller.state.value.error)
        assertTrue(backup.imported.isEmpty())
    }

    @Test
    fun `a file over the limit is reported and not decoded`() = runTest {
        val backup = FakeBackup().apply { readError = ArchiveTooLargeException() }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.importFrom(TARGET)
        advanceUntilIdle()

        assertEquals(1, backup.readCalls)
        assertEquals(Texts.BACKUP_TOO_LARGE, controller.state.value.error)
        assertTrue(backup.imported.isEmpty())
        assertFalse(controller.state.value.busy)
    }

    @Test
    fun `an engine failure is shown as a failed operation`() = runTest {
        val backup = FakeBackup().apply {
            fileBytes = encodeArchive(snapshotOf(trips = 1))
            importError = IllegalStateException("archive is unreadable")
        }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.importFrom(TARGET)
        advanceUntilIdle()

        assertEquals(Texts.BACKUP_FAILED, controller.state.value.error)
        assertFalse(controller.state.value.busy, "the buttons must work again")
    }

    @Test
    fun `a second request while busy is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backup = FakeBackup().apply { writeGate = gate }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.exportTo(TARGET)
        advanceUntilIdle()
        assertTrue(controller.state.value.busy)

        controller.exportTo(TARGET)
        controller.importFrom(TARGET)
        advanceUntilIdle()
        assertEquals(1, backup.exportCalls)
        assertEquals(0, backup.readCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(controller.state.value.busy)
    }

    @Test
    fun `reset drops a result that arrives late`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backup = FakeBackup().apply { writeGate = gate }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.exportTo(TARGET)
        advanceUntilIdle()

        controller.reset()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(BackupUiState(), controller.state.value, "a result of the old session may not fill the screen again")
        assertEquals(listOf(EXPORTED_AT), backup.savedDates, "the operation itself was already finished when the user logged out")
    }

    @Test
    fun `the stored date is read once`() = runTest {
        val backup = FakeBackup().apply { lastExport = OLD_EXPORT }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.ensureLoaded()
        advanceUntilIdle()

        // A rotation restarts the effect of AppRoot; the loaded flag has to stop the next read.
        controller.ensureLoaded()
        controller.open()
        controller.ensureLoaded()
        advanceUntilIdle()

        assertEquals(1, backup.readDateCalls)
        assertEquals(OLD_EXPORT, controller.state.value.lastExport)
        assertTrue(controller.state.value.loaded)
    }

    @Test
    fun `two calls before the first result read the date once`() = runTest {
        val backup = FakeBackup().apply { lastExport = OLD_EXPORT }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        // AppRoot and the backup screen may both ask before the first read is back.
        controller.ensureLoaded()
        controller.ensureLoaded()
        advanceUntilIdle()

        assertEquals(1, backup.readDateCalls)
        assertEquals(OLD_EXPORT, controller.state.value.lastExport)
    }

    @Test
    fun `a settings read that fails shows no date but unblocks the screen`() = runTest {
        val backup = FakeBackup().apply { readDateError = IllegalStateException("settings file is gone") }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.open()
        advanceUntilIdle()

        val state = controller.state.value
        assertNull(state.lastExport)
        assertTrue(state.loaded, "the screen must not wait forever for a date that will not come")
    }

    @Test
    fun `opening clears the result of the previous operation`() = runTest {
        val backup = FakeBackup().apply { fileBytes = "not an archive".toByteArray(Charsets.UTF_8) }
        val controller = controller(backup, StandardTestDispatcher(testScheduler))

        controller.importFrom(TARGET)
        advanceUntilIdle()
        assertEquals(Texts.BACKUP_CORRUPT, controller.state.value.error)

        controller.open()

        assertNull(controller.state.value.error, "opening the screen clears the old failure at once")
        assertNull(controller.state.value.message)

        advanceUntilIdle()
        assertEquals(1, backup.readDateCalls, "the date was not loaded yet, so opening reads it")
    }
}
