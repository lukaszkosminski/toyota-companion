package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the "automatic copy" card shows (PRD F60, docs/SPEC.md §60): the folder the owner picked, the day of the last automatic
 * copy and whether the last attempt failed.
 */
data class AutoBackupUiState(
    val folderName: String? = null,
    val lastCopy: LocalDate? = null,
    val failed: Boolean = false,
)

/**
 * The automatic archive copy (PRD F60, docs/SPEC.md §60): after a successful synchronization one dated JSON file per day is written
 * into the folder the owner picked, the newest [AUTO_COPY_KEEP] kept. The folder is addressed by the opaque text of the system picker,
 * so this class has no Android types and its tests need no Robolectric. A copy counts as a backup, so the date of the last export is
 * stored too and the reminder of §14.3 stops asking.
 *
 * @param readFolder / [saveFolder] the picked folder (`null` = off) in `SettingsStore`
 * @param readLastCopy / [saveLastCopy] the day of the last automatic copy
 * @param folderName the display name of the folder, for the card
 * @param writeCopy writes [bytes] as [name] in the folder
 * @param listCopies the names in the folder (files and folders)
 * @param deleteCopy removes the file [name] from the folder
 * @param export the archive as the bytes of the backup file (§14)
 * @param saveLastExport the date stored for the reminder of §14.3
 * @param zone / [today] the day the copy is named after; injected for tests
 * @param io where the folder and the archive are touched — never the UI thread
 */
class AutoBackup(
    private val readFolder: suspend () -> String?,
    private val saveFolder: suspend (String?) -> Unit,
    private val readLastCopy: suspend () -> LocalDate?,
    private val saveLastCopy: suspend (LocalDate?) -> Unit,
    private val folderName: suspend (folder: String) -> String?,
    private val writeCopy: suspend (folder: String, name: String, bytes: ByteArray) -> Unit,
    private val listCopies: suspend (folder: String) -> List<String>,
    private val deleteCopy: suspend (folder: String, name: String) -> Unit,
    private val export: suspend () -> ByteArray,
    private val saveLastExport: suspend (Instant) -> Unit,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(AutoBackupUiState())
    val state: StateFlow<AutoBackupUiState> = ui.asStateFlow()

    /** One copy at a time: a sync and a screen visit can both ask, and two writes of the same day must not race. */
    private val lock = Mutex()

    /** Reads the folder and the last day for the card; a failed read leaves the state as it is. */
    fun load() {
        scope.launch {
            val folder = withContext(io) {
                try {
                    readFolder()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            val name = folder?.let { withContext(io) { folderName(it) } }
            val last = withContext(io) {
                try {
                    readLastCopy()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            ui.value = ui.value.copy(folderName = name, lastCopy = last)
        }
    }

    /** Stores the folder the owner picked; the card shows its name and the next sync writes the first copy. */
    fun chooseFolder(folder: String) {
        scope.launch {
            withContext(io) {
                saveFolder(folder)
                // A new folder has no copy of today yet, whatever the old one had: the next sync writes one there.
                saveLastCopy(null)
            }
            ui.value = ui.value.copy(failed = false)
            load()
        }
    }

    /** The system did not give a lasting access to the picked folder: nothing is stored and the card says so. */
    fun folderRefused() {
        ui.value = ui.value.copy(failed = true)
    }

    /** Turns the automatic copy off: the folder is forgotten, its files stay. */
    fun disable() {
        scope.launch {
            withContext(io) {
                saveFolder(null)
                // The day goes with the folder: turning it on again starts from a clean slate.
                saveLastCopy(null)
            }
            ui.value = ui.value.copy(folderName = null, failed = false)
        }
    }

    /**
     * Called after a successful synchronization: writes today's copy when the folder is set and today has none yet, then rotates the
     * folder. A failure is remembered for the card and never breaks the synchronization; the day stays unmarked, so the next sync
     * tries again.
     */
    suspend fun runAfterSync() {
        try {
            lock.withLock {
                val folder = withContext(io) { readFolder() } ?: return@withLock
                val day = today()
                val last = withContext(io) { readLastCopy() }
                if (!shouldCopyToday(hasFolder = true, lastCopy = last, today = day)) return@withLock
                val bytes = export()
                withContext(io) { writeCopy(folder, autoCopyName(day), bytes) }
                withContext(io) {
                    saveLastCopy(day)
                    saveLastExport(Instant.now())
                }
                // The copy is written and counted; tidying the folder afterwards is best effort and never a failed run.
                try {
                    val existing = withContext(io) { listCopies(folder) }
                    for (name in copiesToDelete(existing, justWritten = autoCopyName(day))) {
                        try {
                            withContext(io) { deleteCopy(folder, name) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // An old copy that cannot be removed stays; the next run tries again.
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A folder that cannot be listed keeps its old copies.
                }
                ui.value = ui.value.copy(lastCopy = day, failed = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The text of the exception is not shown (docs/SECURITY.md).
            ui.value = ui.value.copy(failed = true)
        }
    }

    /** A logout drops the card's figures; the folder is a phone setting and stays. */
    fun reset() {
        ui.value = AutoBackupUiState()
    }
}
