package pl.lk.toyotacompanion.ui

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip

/**
 * @param years the years that have trips, newest first
 * @param selected the year on the page
 * @param summary its summary and [previous] that of the year before when it has trips
 */
data class YearSummaryState(
    val loading: Boolean = true,
    val years: List<Int> = emptyList(),
    val selected: Int? = null,
    val summary: YearSummary? = null,
    val previous: YearSummary? = null,
    val loadFailed: Boolean = false,
)

/** The year page (docs/SPEC.md §29): reads the archive when it opens and when another year is chosen. [reset] on logout. */
class YearSummaryController(
    private val read: suspend () -> List<StoredTrip>,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(YearSummaryState())
    val state: StateFlow<YearSummaryState> = ui.asStateFlow()

    private var generation = 0
    private var loadStarted = false

    /** Opens the page on [year] or, without one, on the newest year with trips. */
    fun load(year: Int? = null) {
        loadStarted = true
        val startedIn = generation
        scope.launch {
            val result: Result<YearSummaryState> = try {
                Result.success(
                    withContext(io) {
                        val trips = read()
                        val years = yearsWithTrips(trips, zone())
                        val chosen = year?.takeIf { it in years } ?: years.firstOrNull()
                        YearSummaryState(
                            loading = false,
                            years = years,
                            selected = chosen,
                            summary = chosen?.let { yearSummary(trips, it, zone()) },
                            previous = chosen?.let { yearSummary(trips, it - 1, zone()) },
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
        generation++
        loadStarted = false
        ui.value = YearSummaryState()
    }
}
