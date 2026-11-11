package pl.lk.toyotacompanion.ui

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.TYRE_MAX_KM
import pl.lk.toyotacompanion.core.domain.TYRE_MAX_MONTHS
import pl.lk.toyotacompanion.core.domain.TYRE_MAX_ODOMETER_KM
import pl.lk.toyotacompanion.core.domain.TyreChange
import pl.lk.toyotacompanion.core.domain.TyreChangeCheck
import pl.lk.toyotacompanion.core.domain.TyreReminder
import pl.lk.toyotacompanion.core.domain.TyreSet
import pl.lk.toyotacompanion.core.domain.TyreThreshold
import pl.lk.toyotacompanion.core.domain.TyreUsage
import pl.lk.toyotacompanion.core.domain.checkTyreChange
import pl.lk.toyotacompanion.core.domain.dueTyreReminders
import pl.lk.toyotacompanion.core.domain.formatTyreChanges
import pl.lk.toyotacompanion.core.domain.formatTyreReminderKeys
import pl.lk.toyotacompanion.core.domain.formatTyreThresholds
import pl.lk.toyotacompanion.core.domain.parseTyreChanges
import pl.lk.toyotacompanion.core.domain.parseTyreReminderKeys
import pl.lk.toyotacompanion.core.domain.parseTyreNumber
import pl.lk.toyotacompanion.core.domain.parseTyreThresholds
import pl.lk.toyotacompanion.core.domain.tyreKeysWithoutChange
import pl.lk.toyotacompanion.core.domain.tyreUsage
import pl.lk.toyotacompanion.core.domain.withoutLastTyreChange

/** What the tyre card shows: the fittings with their usage, the thresholds and the reminders due today (PRD F56). */
data class TyreUiState(
    val loading: Boolean = true,
    val changes: List<TyreChange> = emptyList(),
    val usage: TyreUsage? = null,
    val thresholds: Map<TyreSet, TyreThreshold> = TyreSet.entries.associateWith { TyreThreshold(null, null) },
    val due: List<TyreReminder> = emptyList(),
    val failed: Boolean = false,
)

/** What the tyre card shows: the read is running, no fitting exists yet (an invitation), or the sets with their mileage. */
enum class TyreCardMode { LOADING, INVITE, SETS }

/**
 * The state of the tyre card (PRD F56, docs/SPEC.md §56): while the first read runs it is [TyreCardMode.LOADING]; without any fitting (an empty
 * list or no usage yet) it invites the first change, so the card is never empty and the change is always reachable. A failed read with no
 * fittings still invites: the owner can enter a fitting without the archive.
 */
fun tyreCardMode(state: TyreUiState): TyreCardMode = when {
    state.loading -> TyreCardMode.LOADING
    state.changes.isEmpty() || state.usage == null -> TyreCardMode.INVITE
    else -> TyreCardMode.SETS
}

/** Why a fitting or thresholds were not saved; the form shows the matching text. */
enum class TyreFormError { ODOMETER, FUTURE, DUPLICATE, BEFORE_LAST, THRESHOLD }

/**
 * State of the tyre card in "Wydatki" (PRD F56, docs/SPEC.md §56). The fittings and thresholds are phone settings (text, parsed by the core); the
 * usage is read from the archive on [io], like the statistics. Saving a fitting or the thresholds reads the state again. [reset] on logout.
 */
class TyreController(
    private val readTrips: suspend () -> List<StoredTrip>,
    private val readChangesText: () -> String?,
    private val saveChangesText: (String) -> Unit,
    private val readThresholdsText: () -> String?,
    private val saveThresholdsText: (String) -> Unit,
    private val readKeysText: () -> String?,
    private val saveKeysText: (String) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val ui = MutableStateFlow(TyreUiState())
    val state: StateFlow<TyreUiState> = ui.asStateFlow()

    /** The read in flight; a newer one cancels it, so an older result never overwrites a newer one (C22 D5). */
    private var loadJob: Job? = null

    fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val next = try {
                withContext(io) { compute(readTrips()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.value.copy(loading = false, failed = true)
            }
            ui.value = next
        }
    }

    private fun compute(trips: List<StoredTrip>): TyreUiState {
        val changes = parseTyreChanges(readChangesText())
        val thresholds = parseTyreThresholds(readThresholdsText())
        val zone = zone()
        val usage = tyreUsage(changes, trips.map { it.record.trip }, zone)
        return TyreUiState(
            loading = false,
            changes = changes,
            usage = usage,
            thresholds = thresholds,
            due = dueTyreReminders(usage, thresholds, LocalDate.now(zone)),
        )
    }

    /**
     * Saves a fitting of [set] on [date] with the odometer text (optional). `null` when saved, otherwise the reason. A blank odometer is
     * no value; a wrong one is refused, a date in the future, the same day twice or before the last fitting too.
     */
    fun addChange(date: LocalDate, set: TyreSet, odometerText: String): TyreFormError? {
        val existing = parseTyreChanges(readChangesText())
        val odometer = if (odometerText.isBlank()) null else parseTyreNumber(odometerText, TYRE_MAX_ODOMETER_KM) ?: return TyreFormError.ODOMETER
        val error = when (checkTyreChange(existing, date, LocalDate.now(zone()))) {
            TyreChangeCheck.Ok -> null
            TyreChangeCheck.Future -> TyreFormError.FUTURE
            TyreChangeCheck.Duplicate -> TyreFormError.DUPLICATE
            TyreChangeCheck.BeforeLast -> TyreFormError.BEFORE_LAST
        }
        if (error != null) return error
        saveChangesText(formatTyreChanges(existing + TyreChange(date, set, odometer)))
        load()
        return null
    }

    /**
     * Saves the thresholds from the form texts: blank means no reminder of that kind, a wrong or out-of-range number is refused as a whole.
     * `null` when saved.
     */
    fun saveThresholds(texts: Map<TyreSet, Pair<String, String>>): TyreFormError? {
        val parsed = TyreSet.entries.associateWith { set ->
            val (months, km) = texts[set] ?: ("" to "")
            val m = if (months.isBlank()) null else parseTyreNumber(months, TYRE_MAX_MONTHS)?.takeIf { it >= 1 }
            val k = if (km.isBlank()) null else parseTyreNumber(km, TYRE_MAX_KM)?.takeIf { it >= 1 }
            if ((months.isNotBlank() && m == null) || (km.isNotBlank() && k == null)) return TyreFormError.THRESHOLD
            TyreThreshold(m, k)
        }
        saveThresholdsText(formatTyreThresholds(parsed))
        load()
        return null
    }

    /**
     * Removes the newest fitting (a mistaken entry) and forgets the reminders already sent for it, so the same fitting entered again can be
     * reminded again. `false` when there is nothing to remove.
     */
    fun removeLastChange(): Boolean {
        val changes = parseTyreChanges(readChangesText())
        val last = changes.maxByOrNull { it.date } ?: return false
        saveChangesText(formatTyreChanges(withoutLastTyreChange(changes)))
        saveKeysText(formatTyreReminderKeys(tyreKeysWithoutChange(parseTyreReminderKeys(readKeysText()), last)))
        load()
        return true
    }

    fun reset() {
        loadJob?.cancel()
        loadJob = null
        ui.value = TyreUiState()
    }
}
