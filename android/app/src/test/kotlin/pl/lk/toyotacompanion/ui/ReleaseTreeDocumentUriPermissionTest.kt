package pl.lk.toyotacompanion.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.settings.SettingsStore

/**
 * docs/SPEC.md §60 and docs/SECURITY.md: Tests for the release rule of tree document URI permissions
 * when configuring the automatic backup folder (implemented in `AppContainer.kt:564-577`).
 *
 * Android enforces a quota on persistable URI permissions (typically 128 to 512 per app UID).
 * When the user switches to a different folder or disables automatic backups, the persistable
 * read and write grants for the abandoned folder MUST be released via [android.content.ContentResolver.releasePersistableUriPermission].
 *
 * This test verifies:
 * 1. The decision rule: permission is released only when `previous != null && previous != next`.
 * 2. The flags: both [Intent.FLAG_GRANT_READ_URI_PERMISSION] and [Intent.FLAG_GRANT_WRITE_URI_PERMISSION] are released.
 * 3. Graceful handling of [SecurityException] when a grant was already revoked.
 * 4. Integration with [SettingsStore] and [AutoBackup] lifecycle (`chooseFolder`, `disable`).
 */
@RunWith(RobolectricTestRunner::class)
class ReleaseTreeDocumentUriPermissionTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var settingsStore: SettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("test-autobackup-permissions", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        settingsStore = SettingsStore(prefs)
    }

    /**
     * Pure rule governing when a persistable tree document URI permission should be released.
     * Mirrors the condition in `AppContainer.kt:566`.
     */
    private fun shouldReleaseTreeDocumentUriPermission(previous: String?, next: String?): Boolean =
        previous != null && previous != next

    /** Recorded release call. */
    private data class ReleaseCall(val uri: Uri, val flags: Int)

    /** Tracker simulating the ContentResolver release call. */
    private class PermissionReleaser {
        val calls = mutableListOf<ReleaseCall>()
        var throwSecurityException = false

        fun release(uri: Uri, flags: Int) {
            if (throwSecurityException) throw SecurityException("Grant already revoked by user or system")
            calls += ReleaseCall(uri, flags)
        }
    }

    /**
     * Replicates the exact `saveFolder` lambda wired in `AppContainer.kt:563-577`.
     */
    private fun createSaveFolderAction(
        store: SettingsStore,
        releaser: PermissionReleaser,
    ): (String?) -> Unit = { next ->
        val previous = store.autoBackupFolder()
        if (previous != null && previous != next) {
            try {
                releaser.release(
                    previous.toUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // It was already gone.
            }
        }
        store.saveAutoBackupFolder(next)
    }

    // ---------------- Decision rule tests ----------------

    @Test
    fun `permission is not released when selecting the first folder`() {
        assertFalse(
            shouldReleaseTreeDocumentUriPermission(previous = null, next = "content://tree/primary%3AFolderA"),
            "Initial setup has no previous grant to release",
        )
    }

    @Test
    fun `permission is not released when confirming the same folder`() {
        val folder = "content://tree/primary%3AFolderA"
        assertFalse(
            shouldReleaseTreeDocumentUriPermission(previous = folder, next = folder),
            "Re-saving the existing folder must keep the active grant",
        )
    }

    @Test
    fun `permission is released when switching to a different folder`() {
        val folderA = "content://tree/primary%3AFolderA"
        val folderB = "content://tree/primary%3AFolderB"
        assertTrue(
            shouldReleaseTreeDocumentUriPermission(previous = folderA, next = folderB),
            "Switching folders must release the old folder grant to prevent quota leaks",
        )
    }

    @Test
    fun `permission is released when disabling automatic backup`() {
        val folderA = "content://tree/primary%3AFolderA"
        assertTrue(
            shouldReleaseTreeDocumentUriPermission(previous = folderA, next = null),
            "Disabling backup must release the active folder grant",
        )
    }

    @Test
    fun `permission is not released when disabling while already disabled`() {
        assertFalse(
            shouldReleaseTreeDocumentUriPermission(previous = null, next = null),
            "Disabling when no folder was set has nothing to release",
        )
    }

    // ---------------- Flags and action tests ----------------

    @Test
    fun `releasing folder permission requests both read and write flag revocation`() {
        val releaser = PermissionReleaser()
        val saveFolder = createSaveFolderAction(settingsStore, releaser)

        val folderA = "content://tree/primary%3AFolderA"
        val folderB = "content://tree/primary%3AFolderB"

        // Set initial folder: no release
        saveFolder(folderA)
        assertEquals(folderA, settingsStore.autoBackupFolder())
        assertTrue(releaser.calls.isEmpty(), "Initial folder does not release permissions")

        // Switch to folder B: releases folder A with READ and WRITE flags
        saveFolder(folderB)
        assertEquals(folderB, settingsStore.autoBackupFolder())
        assertEquals(1, releaser.calls.size)

        val expectedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val releaseCall = releaser.calls.single()
        assertEquals(folderA.toUri(), releaseCall.uri)
        assertEquals(expectedFlags, releaseCall.flags)
    }

    @Test
    fun `disabling backup releases the active folder grant and clears setting`() {
        val releaser = PermissionReleaser()
        val saveFolder = createSaveFolderAction(settingsStore, releaser)

        val folder = "content://tree/primary%3ABackups"
        saveFolder(folder)
        assertTrue(releaser.calls.isEmpty())

        // Disable backup
        saveFolder(null)
        assertNull(settingsStore.autoBackupFolder())
        assertEquals(1, releaser.calls.size)
        assertEquals(folder.toUri(), releaser.calls.single().uri)
    }

    @Test
    fun `selecting identical folder does not trigger permission release`() {
        val releaser = PermissionReleaser()
        val saveFolder = createSaveFolderAction(settingsStore, releaser)

        val folder = "content://tree/primary%3ABackups"
        saveFolder(folder)
        saveFolder(folder)
        saveFolder(folder)

        assertEquals(folder, settingsStore.autoBackupFolder())
        assertTrue(releaser.calls.isEmpty(), "No release call when re-saving same folder")
    }

    @Test
    fun `security exception during release is swallowed and does not break setting save`() {
        val releaser = PermissionReleaser().apply { throwSecurityException = true }
        val saveFolder = createSaveFolderAction(settingsStore, releaser)

        val folderA = "content://tree/primary%3AFolderA"
        val folderB = "content://tree/primary%3AFolderB"

        // Seed with folder A directly
        settingsStore.saveAutoBackupFolder(folderA)

        // Switching to folder B attempts release, catches SecurityException, and successfully stores folder B
        saveFolder(folderB)
        assertEquals(folderB, settingsStore.autoBackupFolder(), "New folder must be saved even if release threw SecurityException")
    }

    // ---------------- Integration with AutoBackup controller ----------------

    private fun TestScope.createAutoBackup(
        store: SettingsStore,
        releaser: PermissionReleaser,
        folderDisplayName: String? = "Kopia zapasowa",
    ): AutoBackup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val saveFolder = createSaveFolderAction(store, releaser)
        return AutoBackup(
            readFolder = { store.autoBackupFolder() },
            saveFolder = saveFolder,
            readLastCopy = { store.autoBackupLastDay() },
            saveLastCopy = { store.saveAutoBackupLastDay(it) },
            folderName = { folderDisplayName },
            writeCopy = { _, _, _ -> },
            listCopies = { emptyList() },
            deleteCopy = { _, _ -> },
            export = { ByteArray(16) },
            saveLastExport = {},
            zone = { ZoneId.of("Europe/Warsaw") },
            today = { LocalDate.parse("2026-11-12") },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
        )
    }

    @Test
    fun `AutoBackup chooseFolder and disable coordinate permission release lifecycle`() = runTest {
        val releaser = PermissionReleaser()
        val autoBackup = createAutoBackup(settingsStore, releaser)

        val folder1 = "content://tree/primary%3AFirst"
        val folder2 = "content://tree/primary%3ASecond"

        // 1. Initial chooseFolder
        autoBackup.chooseFolder(folder1)
        advanceUntilIdle()
        assertEquals(folder1, settingsStore.autoBackupFolder())
        assertTrue(releaser.calls.isEmpty())
        assertFalse(autoBackup.state.value.failed)

        // 2. Change folder
        autoBackup.chooseFolder(folder2)
        advanceUntilIdle()
        assertEquals(folder2, settingsStore.autoBackupFolder())
        assertEquals(1, releaser.calls.size)
        assertEquals(folder1.toUri(), releaser.calls.first().uri)

        // 3. Disable
        autoBackup.disable()
        advanceUntilIdle()
        assertNull(settingsStore.autoBackupFolder())
        assertEquals(2, releaser.calls.size)
        assertEquals(folder2.toUri(), releaser.calls.last().uri)
        assertNull(autoBackup.state.value.folderName)
    }
}
