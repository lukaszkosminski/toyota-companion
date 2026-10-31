package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
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
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.ElectricRanking
import pl.lk.toyotacompanion.core.domain.PeriodKind
import pl.lk.toyotacompanion.core.domain.PeriodStats
import pl.lk.toyotacompanion.core.domain.aggregateElectricShare
import pl.lk.toyotacompanion.core.domain.electricTripRanking
import pl.lk.toyotacompanion.core.domain.periodStats
import pl.lk.toyotacompanion.core.domain.yearOverYear

/**
 * Everything the statistics screen shows, computed once per read (docs/SPEC.md §16-§17).
 *
 * @param starts start of each trip by id, for the date of a ranking row
 * @param yearOverYear current year so far against the same days a year before, with the
 *   last day of that window
 */
data class StatsView(
    val evShare: BigDecimal?,
    val ranking: ElectricRanking,
    val starts: Map<String, Instant>,
    val years: List<PeriodStats>,
    val seasons: List<PeriodStats>,
    val yearOverYear: Pair<PeriodStats, PeriodStats>?,
    val windowEnd: LocalDate?,
)

/**
 * @param loading the first read is still running
 * @param view the statistics; `null` until read
 * @param loadFailed the read failed; the text comes from [Texts], never the exception
 */
data class StatsUiState(val loading: Boolean = true, val view: StatsView? = null, val loadFailed: Boolean = false)

/**
 * Builds [StatsView] from the trips of the selected vehicle (`SyncEngine.trips`), in the
 * zone of the trips list. Read in the navigation callback, like the refuel log, so a
 * rotation does not read again; [ensureLoaded] covers a process death.
 */
class StatsController(
    private val read: suspend () -> List<StoredTrip>,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = ui.asStateFlow()

    private var generation = 0
    private var loadStarted = false

    fun load() {
        loadStarted = true
        val startedIn = generation
        scope.launch {
            val result: Result<StatsView> = try {
                Result.success(withContext(io) { computeStats(read(), zone()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != startedIn) return@launch
            ui.value = if (result.isSuccess) {
                StatsUiState(loading = false, view = result.getOrThrow())
            } else {
                ui.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    fun ensureLoaded() {
        if (!loadStarted) load()
    }

    fun reset() {
        generation++
        loadStarted = false
        ui.value = StatsUiState()
    }
}

/** The pure part of [StatsController]: one pass of the domain functions over the trips. */
fun computeStats(stored: List<StoredTrip>, zone: ZoneId): StatsView {
    val trips = stored.map { it.record.trip }
    val costs = stored.mapNotNull { it.cost }
    val yoy = yearOverYear(trips, costs, zone)
    return StatsView(
        evShare = aggregateElectricShare(trips),
        ranking = electricTripRanking(trips),
        starts = trips.associate { it.id to it.start },
        years = periodStats(trips, costs, zone, PeriodKind.YEAR),
        seasons = periodStats(trips, costs, zone, PeriodKind.SEASON),
        yearOverYear = yoy,
        windowEnd = if (yoy == null) null else trips.maxOf { it.start.atZone(zone).toLocalDate() },
    )
}
