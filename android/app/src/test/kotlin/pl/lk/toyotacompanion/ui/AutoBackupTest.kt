package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/** A folder in memory, standing for the system document provider. */
private class FakeFolder {
    var uri: String? = "content://tree/home"
    var name: String? = "Kopie"
    var lastCopy: LocalDate? = null
    var files = LinkedHashMap<String, Int>()
    var exports = 0
    var lastExportAt: Instant? = null
    var failWrite = false
    var failList = false
    var failRead = false
    val deleted = mutableListOf<String>()

    fun names(): List<String> = files.keys.toList()
}

/** docs/SPEC.md §60: the automatic copy on a fake folder and a fake archive. */
class AutoBackupTest {
    private fun TestScope.autoBackup(fake: FakeFolder): AutoBackup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AutoBackup(
            readFolder = {
                if (fake.failRead) throw IllegalStateException("unreadable")
                fake.uri
            },
            saveFolder = { fake.uri = it },
            readLastCopy = { fake.lastCopy },
            saveLastCopy = { fake.lastCopy = it },
            folderName = { fake.name },
            writeCopy = { _, name, bytes ->
                if (fake.failWrite) throw IllegalStateException("no write")
                fake.files[name] = bytes.size
            },
            listCopies = {
                if (fake.failList) throw IllegalStateException("no list")
                fake.names()
            },
            deleteCopy = { _, name ->
                fake.files.remove(name)
                fake.deleted += name
            },
            export = {
                fake.exports++
                ByteArray(64)
            },
            saveLastExport = { fake.lastExportAt = it },
            zone = { WARSAW },
            today = { LocalDate.parse("2026-11-11") },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
        )
    }

    @Test
    fun `a copy is written once a day and counts as a backup`() = runTest {
        val fake = FakeFolder()
        val a = autoBackup(fake)

        a.runAfterSync()
        advanceUntilIdle()

        assertEquals(listOf("toyota-companion-2026-11-11.json"), fake.names())
        assertEquals(1, fake.exports)
        assertEquals(LocalDate.parse("2026-11-11"), fake.lastCopy)
        assertTrue(fake.lastExportAt != null, "the reminder of §14.3 sees the copy")
        assertFalse(a.state.value.failed)
    }

    @Test
    fun `a second synchronization the same day writes nothing`() = runTest {
        val fake = FakeFolder()
        val a = autoBackup(fake)
        a.runAfterSync()
        advanceUntilIdle()
        a.runAfterSync()
        advanceUntilIdle()

        assertEquals(1, fake.exports)
        assertEquals(1, fake.names().size)
    }

    @Test
    fun `without a folder nothing is exported`() = runTest {
        val fake = FakeFolder().apply { uri = null }
        val a = autoBackup(fake)
        a.runAfterSync()
        advanceUntilIdle()

        assertEquals(0, fake.exports)
        assertTrue(fake.names().isEmpty())
    }

    @Test
    fun `a failed write does not mark the day and is remembered for the card`() = runTest {
        val fake = FakeFolder().apply { failWrite = true }
        val a = autoBackup(fake)
        a.runAfterSync()
        advanceUntilIdle()

        assertTrue(a.state.value.failed)
        assertNull(fake.lastCopy, "the next synchronization tries again")
        assertNull(fake.lastExportAt)
    }

    @Test
    fun `an old copy that cannot be listed or removed is not a failed run`() = runTest {
        val fake = FakeFolder().apply {
            files["toyota-companion-2026-11-10.json"] = 64
            failList = true
        }
        val a = autoBackup(fake)
        a.runAfterSync()
        advanceUntilIdle()

        assertFalse(a.state.value.failed, "the copy itself was written")
        assertEquals(LocalDate.parse("2026-11-11"), fake.lastCopy)
    }

    @Test
    fun `the folder is rotated to the newest copies only`() = runTest {
        val fake = FakeFolder().apply {
            files["toyota-companion-2026-10-31.json"] = 64
            for (day in 1..10) files["toyota-companion-2026-11-%02d.json".format(day)] = 64
            files["notes.txt"] = 10
        }
        val a = autoBackup(fake)
        a.runAfterSync()
        advanceUntilIdle()

        // 11 old copies plus today's make 12: the two oldest go, the foreign file stays.
        assertEquals(
            listOf("toyota-companion-2026-10-31.json", "toyota-companion-2026-11-01.json"),
            fake.deleted.sorted(),
        )
        assertEquals(AUTO_COPY_KEEP + 1, fake.names().size, "ten copies and the foreign file")
        assertTrue("toyota-companion-2026-11-11.json" in fake.names())
        assertTrue("notes.txt" in fake.names())
    }

    @Test
    fun `choosing a folder shows its name and turning it off forgets it`() = runTest {
        val fake = FakeFolder().apply {
            uri = null
            files["toyota-companion-2026-11-10.json"] = 64
        }
        val a = autoBackup(fake)
        a.chooseFolder("content://tree/home")
        advanceUntilIdle()
        assertEquals("Kopie", a.state.value.folderName)
        assertEquals("content://tree/home", fake.uri)

        a.disable()
        advanceUntilIdle()
        assertNull(a.state.value.folderName)
        assertNull(fake.uri)
        assertNull(fake.lastCopy, "the day goes with the folder")
        assertEquals(1, fake.names().size, "the files in the folder stay")
    }

    @Test
    fun `an unreadable folder setting leaves the card empty and the sync does nothing`() = runTest {
        val fake = FakeFolder().apply { failRead = true }
        val a = autoBackup(fake)
        a.load()
        advanceUntilIdle()
        assertNull(a.state.value.folderName)

        a.runAfterSync()
        advanceUntilIdle()
        assertEquals(0, fake.exports)
    }

    @Test
    fun `a changed folder gets its own copy the same day`() = runTest {
        val fake = FakeFolder()
        val a = autoBackup(fake)
        a.runAfterSync()
        advanceUntilIdle()
        assertEquals(1, fake.exports)

        fake.files.clear()
        a.chooseFolder("content://tree/other")
        advanceUntilIdle()
        assertNull(a.state.value.lastCopy, "the card does not claim a copy the new folder does not have")
        a.runAfterSync()
        advanceUntilIdle()

        assertEquals(2, fake.exports)
        assertEquals(listOf("toyota-companion-2026-11-11.json"), fake.names())
    }
}
