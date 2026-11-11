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
import pl.lk.toyotacompanion.settings.CarValuation

/**
 * @param years the years that have trips, newest first
 * @param selected the year on the page
 * @param summary its summary and [previous] that of the year before when it has trips
 * @param ownership the total cost of the year and [previousOwnership] that of the year before (docs/SPEC.md §39)
 */
data class YearSummaryState(
    val loading: Boolean = true,
    val years: List<Int> = emptyList(),
    val selected: Int? = null,
    val summary: YearSummary? = null,
    val previous: YearSummary? = null,
    val ownership: YearOwnership? = null,
    val previousOwnership: YearOwnership? = null,
    val loadFailed: Boolean = false,
)

/** The year page (docs/SPEC.md §29): reads the archive when it opens and when another year is chosen. [reset] on logout. */
class YearSummaryController(
    private val read: suspend () -> List<StoredTrip>,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val readExpenses: suspend () -> List<Expense> = { emptyList() },
    /** The owner's car valuation (PRD F55, docs/SPEC.md §55); `null` leaves the total cost as cash only. */
    private val readValuation: suspend () -> CarValuation? = { null },
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val ui = MutableStateFlow(YearSummaryState())
    val state: StateFlow<YearSummaryState> = ui.asStateFlow()

    private var generation = 0

    /** The read in flight; a newer one cancels it, so an older result never overwrites a newer one (C22 D5). */
    private var loadJob: Job? = null
    private var loadStarted = false

    /** Opens the page on [year] or, without one, on the newest year with trips. */
    fun load(year: Int? = null) {
        loadStarted = true
        val startedIn = generation
        loadJob?.cancel()
        loadJob = scope.launch {
            val result: Result<YearSummaryState> = try {
                Result.success(
                    withContext(io) {
                        val trips = read()
                        val expenses = readExpenses()
                        val valuation = readValuation()
                        val years = yearsWithTrips(trips, zone())
                        val chosen = year?.takeIf { it in years } ?: years.firstOrNull()
                        val day = today()
                        YearSummaryState(
                            loading = false,
                            years = years,
                            selected = chosen,
                            summary = chosen?.let { yearSummary(trips, it, zone()) },
                            previous = chosen?.let { yearSummary(trips, it - 1, zone()) },
                            ownership = chosen?.let { yearOwnership(trips, expenses, it, zone(), day, valuation) },
                            previousOwnership = chosen?.let { yearOwnership(trips, expenses, it - 1, zone(), day, valuation) },
                        )
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = result.getOrElse { ui.value.copy(loading = false, loadFailed = true) }
        }
    }

    fun ensureLoaded() {
        if (!loadStarted) load()
    }

    fun reset() {
        loadJob?.cancel()
        loadJob = null
        generation++
        loadStarted = false
        ui.value = YearSummaryState()
    }
}
