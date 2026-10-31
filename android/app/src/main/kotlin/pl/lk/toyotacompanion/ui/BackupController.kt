package pl.lk.toyotacompanion.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.ArchiveDecode
import pl.lk.toyotacompanion.core.data.ArchiveSnapshot
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.decodeArchive
import pl.lk.toyotacompanion.core.data.encodeArchive
import pl.lk.toyotacompanion.core.sync.ArchiveImport
import pl.lk.toyotacompanion.core.sync.ArchiveImportCounts

/** The largest file the import reads into memory; a real archive is hundreds of kB (docs/SPEC.md §14). */
const val ARCHIVE_IMPORT_LIMIT_BYTES: Int = 20 * 1024 * 1024

/** The chosen file is larger than [ARCHIVE_IMPORT_LIMIT_BYTES]; nothing was read past the limit. */
class ArchiveTooLargeException : Exception("archive file too large")

/**
 * Reads [input] up to [limit] bytes; one byte more means the file is not an archive
 * of this app and nothing past the limit is kept in memory (docs/SPEC.md §14.1).
 */
fun readArchiveBytes(input: InputStream, limit: Int = ARCHIVE_IMPORT_LIMIT_BYTES): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw ArchiveTooLargeException()
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** What the status screen says about the last backup (PRD F8, docs/SPEC.md §14.3). */
sealed class BackupReminder {
    /** A recent copy exists, or there is nothing to copy yet. */
    data object None : BackupReminder()

    /** The archive has trips and was never exported. */
    data object Never : BackupReminder()

    /** The last copy is older than [BACKUP_REMINDER_DAYS]. */
    data class Overdue(val days: Long) : BackupReminder()
}

/** Date of a backup for the screen, e.g. `31.10.2026`, in [zone]; no `Locale` (docs/SPEC.md §14.3). */
fun backupDate(at: Instant, zone: ZoneId): String {
    val day = at.atZone(zone).toLocalDate()
    // No String.format: the device locale could change the digits (see TripsFormatting).
    return day.dayOfMonth.toString().padStart(2, '0') + "." +
        day.monthValue.toString().padStart(2, '0') + "." + day.year.toString()
}

/** Days after which the status screen asks for a new copy (PRD F8). */
const val BACKUP_REMINDER_DAYS: Long = 30

/**
 * The F8 rule, pure (docs/SPEC.md §14.3): an empty archive needs no copy; a
 * non-empty one without any copy, or with one older than [BACKUP_REMINDER_DAYS]
 * whole days, gets the reminder. A copy dated in the future (a clock moved back)
 * counts as recent.
 */
fun backupReminder(lastExport: Instant?, now: Instant, tripsStored: Int): BackupReminder {
    if (tripsStored <= 0) return BackupReminder.None
    if (lastExport == null) return BackupReminder.Never
    val days = Duration.between(lastExport, now).toDays()
    return if (days > BACKUP_REMINDER_DAYS) BackupReminder.Overdue(days) else BackupReminder.None
}

/**
 * What the backup screen shows (docs/SPEC.md §14.2).
 *
 * @param lastExport when the archive was last exported, `null` when never (or unknown yet)
 * @param loaded the date of the last export has been read
 * @param busy an export or import is running; both buttons stay blocked
 * @param message the Polish result of the last operation
 * @param error why the last operation failed, in Polish; never the exception text
 */
data class BackupUiState(
    val lastExport: Instant? = null,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/**
 * Export and import of the whole archive (PRD F7) and the date of the last
 * export (F8), following [DefaultPriceController]: application scope, a
 * generation counter, [reset] on logout. Files are addressed by an opaque text
 * (the `Uri` of the system file picker), so this class has no Android types and
 * its tests need no Robolectric.
 *
 * @param export reads the archive (`SyncEngine.exportArchive`)
 * @param import adds a decoded archive (`SyncEngine.importArchive`)
 * @param writeFile writes the bytes to the chosen file
 * @param readFile reads the chosen file; throws [ArchiveTooLargeException] past the limit
 * @param readLastExport / [saveLastExport] the date in `SettingsStore`
 * @param loadTrips the trips of the selected vehicle for the CSV (`SyncEngine.trips`, docs/SPEC.md §14.4)
 * @param zone the zone of the trips list, so the CSV shows the same times
 */
class BackupController(
    private val export: suspend () -> ArchiveSnapshot,
    private val import: suspend (ArchiveSnapshot) -> ArchiveImport,
    private val writeFile: suspend (target: String, bytes: ByteArray) -> Unit,
    private val readFile: suspend (source: String) -> ByteArray,
    private val readLastExport: suspend () -> Instant?,
    private val saveLastExport: suspend (Instant) -> Unit,
    private val loadTrips: suspend () -> List<StoredTrip>,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = ui.asStateFlow()

    /** Bumped by [reset]; a late result of the old session is dropped. */
    private var generation = 0

    /** A read of the date is running or done; a second call before its result reads nothing. */
    private var loadStarted = false

    /**
     * Reads the date of the last export once; `AppRoot` calls this from an effect that
     * restarts on every Activity recreation, so the guard keeps rotations from reading
     * the settings file again (as docs/SPEC.md §12.3).
     */
    fun ensureLoaded() {
        if (ui.value.loaded || loadStarted) return
        loadStarted = true
        val startedIn = generation
        scope.launch {
            val date = try {
                withContext(io) { readLastExport() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (generation != startedIn) return@launch
            ui.value = ui.value.copy(lastExport = date, loaded = true)
        }
    }

    /** Opens the screen: the result of an earlier operation is gone. */
    fun open() {
        ui.value = ui.value.copy(message = null, error = null)
        ensureLoaded()
    }

    /** Writes the whole archive to [target]; the date is stored only after the file was written. */
    fun exportTo(target: String) {
        if (ui.value.busy) return
        launchOperation { exportArchiveTo(target) }
    }

    /**
     * Writes the trips of the selected vehicle as CSV to [target] (PRD F6, docs/SPEC.md §14.4).
     * Not a backup: the date of the last archive copy stays as it is.
     */
    fun exportTripsCsv(target: String) {
        if (ui.value.busy) return
        launchOperation { exportCsvTo(target) }
    }

    /** Reads [source] and adds what is missing from the phone (docs/SPEC.md §14.1). */
    fun importFrom(source: String) {
        if (ui.value.busy) return
        launchOperation { importArchiveFrom(source) }
    }

    /** Logout: the state in memory is forgotten, the stored date stays (it belongs to the phone). */
    fun reset() {
        generation++
        loadStarted = false
        ui.value = BackupUiState()
    }

    private fun launchOperation(operation: suspend () -> BackupResult) {
        val startedIn = generation
        ui.value = ui.value.copy(busy = true, message = null, error = null)
        scope.launch {
            val result = try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The text of the exception may name the file or its content; never shown.
                BackupResult.Failed(Texts.BACKUP_FAILED)
            }
            if (generation != startedIn) return@launch
            ui.value = when (result) {
                is BackupResult.CsvExported -> ui.value.copy(busy = false, message = Texts.csvExported(result.trips))
                is BackupResult.Exported -> ui.value.copy(
                    busy = false,
                    lastExport = result.at,
                    loaded = true,
                    message = Texts.backupExported(result.trips),
                )
                is BackupResult.Imported -> ui.value.copy(busy = false, message = Texts.backupImported(result.counts))
                is BackupResult.Failed -> ui.value.copy(busy = false, error = result.message)
            }
        }
    }

    private suspend fun exportArchiveTo(target: String): BackupResult {
        val snapshot = withContext(io) { export() }
        withContext(io) { writeFile(target, encodeArchive(snapshot)) }
        withContext(io) { saveLastExport(snapshot.exportedAt) }
        return BackupResult.Exported(snapshot.exportedAt, snapshot.trips.size)
    }

    private suspend fun exportCsvTo(target: String): BackupResult {
        val trips = withContext(io) { loadTrips() }
        // The button is disabled without trips; a list that emptied meanwhile writes nothing.
        if (trips.isEmpty()) return BackupResult.Failed(Texts.CSV_NO_TRIPS)
        val bytes = (CSV_BOM + tripsCsv(trips, zone())).toByteArray(Charsets.UTF_8)
        withContext(io) { writeFile(target, bytes) }
        return BackupResult.CsvExported(trips.size)
    }

    private suspend fun importArchiveFrom(source: String): BackupResult {
        val bytes = try {
            withContext(io) { readFile(source) }
        } catch (e: ArchiveTooLargeException) {
            return BackupResult.Failed(Texts.BACKUP_TOO_LARGE)
        }
        return when (val decoded = withContext(io) { decodeArchive(bytes) }) {
            ArchiveDecode.Unsupported -> BackupResult.Failed(Texts.BACKUP_UNSUPPORTED)
            ArchiveDecode.Corrupt -> BackupResult.Failed(Texts.BACKUP_CORRUPT)
            is ArchiveDecode.Ok -> when (val done = withContext(io) { import(decoded.snapshot) }) {
                is ArchiveImport.Done -> BackupResult.Imported(done.counts)
            }
        }
    }

    private sealed class BackupResult {
        data class Exported(val at: Instant, val trips: Int) : BackupResult()
        data class CsvExported(val trips: Int) : BackupResult()
        data class Imported(val counts: ArchiveImportCounts) : BackupResult()
        data class Failed(val message: String) : BackupResult()
    }
}
