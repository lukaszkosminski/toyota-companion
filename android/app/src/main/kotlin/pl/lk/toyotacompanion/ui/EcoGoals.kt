package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
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

/** A month with fewer kilometres than this is not judged: a few short trips say more about the trips than about the goal. */
const val ECO_MIN_MONTH_KM = 50.0


/** The two optional goals of the owner (docs/SPEC.md §40); `null` = no such goal. */
data class EcoGoals(
    /** The month's consumption should be at most this, l/100 km (0 < x <= 20, two decimals). */
    val maxConsumption: BigDecimal? = null,
    /** The share of the month's kilometres driven on electricity should be at least this, whole percent 1-100. */
    val minEvSharePct: Int? = null,
    /** The first month the consumption goal counts for the streak (PRD F58); `null` = from the start of the archive. */
    val consumptionSince: MonthKey? = null,
    /** The first month the electric goal counts for the streak (PRD F58); `null` = from the start of the archive. */
    val evSince: MonthKey? = null,
) {
    val any: Boolean get() = maxConsumption != null || minEvSharePct != null
}

private fun sameFigure(a: BigDecimal?, b: BigDecimal?): Boolean =
    if (a == null || b == null) a == b else a.compareTo(b) == 0

/** Result of reading the typed electric-share goal. */
sealed interface EvGoalCheck {
    data class Valid(val pct: Int) : EvGoalCheck

    /** An empty field removes the goal. */
    data object Empty : EvGoalCheck

    data object Invalid : EvGoalCheck
}

/** `40` or `40 %` -> 40; whole percent 1-100. A fraction, zero, a negative number or text is invalid, never clamped. */
fun parseEvGoal(text: String): EvGoalCheck {
    if (text.isBlank()) return EvGoalCheck.Empty
    val trimmed = text.trim().removeSuffix("%").trim()
    if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' } || trimmed.length > 3) return EvGoalCheck.Invalid
    val value = trimmed.toInt()
    return if (value in 1..100) EvGoalCheck.Valid(value) else EvGoalCheck.Invalid
}

/** One goal against what the month has so far: [met] is `null` when the month is too short or has no figure to judge. */
data class GoalResult(val goal: BigDecimal, val actual: BigDecimal?, val met: Boolean?)

/**
 * One calendar month against the goals. [enoughDriving] is false under [ECO_MIN_MONTH_KM]; then no goal has a verdict.
 * [evKnown] is false when no trip of the month reports electric driving (no data is not 0 %, docs/SPEC.md §29).
 */
data class EcoMonth(
    val month: MonthKey,
    val km: BigDecimal,
    val enoughDriving: Boolean,
    val consumption: GoalResult?,
    val ev: GoalResult?,
    val evKnown: Boolean,
)

/**
 * The goals and the figures of one calendar month in [zone]: consumption = Σ fuel / Σ km of the month's trips (0.01 HALF_UP), the
 * electric share as in §29 (each trip's electric km clipped to its own distance, whole percent HALF_UP). Trips with a non-finite or
 * non-positive distance or a negative fuel reading are left out, like on the status card.
 */
fun ecoMonth(goals: EcoGoals, trips: List<StoredTrip>, zone: ZoneId, month: MonthKey): EcoMonth =
    // One rule for both entry points: the month is cut out here, the sums are in the overload that takes a month's trips.
    ecoMonth(
        goals,
        trips.filter { stored ->
            val day = stored.record.trip.start.atZone(zone)
            day.year == month.year && day.monthValue == month.month
        },
        month,
    )

/** The previous calendar month; the year rolls over in January. */
fun previousMonth(month: MonthKey): MonthKey =
    if (month.month == 1) MonthKey(month.year - 1, 12) else MonthKey(month.year, month.month - 1)

/** What the status card and the widget show: the month in progress and, when it had trips, the one before it. */
data class EcoProgress(val current: EcoMonth, val previous: EcoMonth?)

/** [previous] is `null` when the previous month had no trip at all (then there is nothing to report). */
fun ecoProgress(goals: EcoGoals, trips: List<StoredTrip>, zone: ZoneId, today: LocalDate): EcoProgress? {
    if (!goals.any) return null
    val now = MonthKey(today.year, today.monthValue)
    val before = ecoMonth(goals, trips, zone, previousMonth(now))
    return EcoProgress(
        current = ecoMonth(goals, trips, zone, now),
        previous = if (before.km.signum() > 0) before else null,
    )
}

private fun mark(met: Boolean): String = if (met) "✓" else "✗"

/** `Spalanie: 4,3 / 4,5 l/100 km ✓` of the month in progress; `null` without that goal or without a figure. */
fun ecoConsumptionLine(month: EcoMonth): String? {
    val result = month.consumption ?: return null
    val actual = result.actual ?: return null
    return Texts.ecoConsumptionLine(
        TripsFormatting.number(actual, 2),
        TripsFormatting.number(result.goal, 2),
        result.met?.let { mark(it) },
    )
}

/** `Jazda na prądzie: 42 / 40 % ✓`; "no data this month" when no trip reports electric driving; `null` without that goal. */
fun ecoEvLine(month: EcoMonth): String? {
    val result = month.ev ?: return null
    val actual = result.actual ?: return Texts.ECO_NO_EV_DATA
    return Texts.ecoEvLine(TripsFormatting.number(actual, 0), TripsFormatting.number(result.goal, 0), result.met?.let { mark(it) })
}

/** The lines of the card for the month in progress: the figures, or one "too little driving" line. */
fun ecoCurrentLines(progress: EcoProgress): List<String> {
    val month = progress.current
    if (!month.enoughDriving) return listOf(Texts.ecoTooLittle(TripsFormatting.number(month.km, 0)))
    return listOfNotNull(ecoConsumptionLine(month), ecoEvLine(month))
}

/** `Poprzedni miesiąc: cel spalania – osiągnięty; cel jazdy na prądzie – nieosiągnięty`; `null` without a previous month with trips. */
fun ecoPreviousLine(progress: EcoProgress): String? {
    val month = progress.previous ?: return null
    if (!month.enoughDriving) return Texts.ecoPreviousTooLittle(TripsFormatting.number(month.km, 0))
    val parts = listOfNotNull(
        month.consumption?.met?.let { Texts.ecoPreviousConsumption(it) },
        month.ev?.met?.let { Texts.ecoPreviousEv(it) },
    )
    return if (parts.isEmpty()) null else Texts.ecoPrevious(parts.joinToString(Texts.SEPARATOR))
}

/** The fourth, hideable line of the widget (docs/SPEC.md §32, §40): `Cel: 4,3 / 4,5 l/100 km ✓`; empty without a goal. */
fun ecoWidgetLine(progress: EcoProgress?): String {
    if (progress == null) return ""
    val month = progress.current
    if (!month.enoughDriving) return Texts.ecoWidget(Texts.ecoWidgetTooLittle(TripsFormatting.number(month.km, 0)))
    val parts = ArrayList<String>()
    val consumption = month.consumption
    val consumptionActual = consumption?.actual
    if (consumption != null && consumptionActual != null) {
        parts += Texts.ecoWidgetConsumption(
            TripsFormatting.number(consumptionActual, 2),
            TripsFormatting.number(consumption.goal, 2),
            consumption.met?.let(::mark),
        )
    }
    val ev = month.ev
    val evActual = ev?.actual
    if (ev != null && evActual != null) {
        parts += Texts.ecoWidgetEv(TripsFormatting.number(evActual, 0), TripsFormatting.number(ev.goal, 0), ev.met?.let(::mark))
    }
    return if (parts.isEmpty()) "" else Texts.ecoWidget(parts.joinToString(Texts.SEPARATOR))
}

/** What the card needs: the goals and, once the trips were read, the month's figures. */
data class EcoGoalsState(
    val goals: EcoGoals = EcoGoals(),
    val progress: EcoProgress? = null,
    val history: EcoHistory? = null,
)

/**
 * The eco goals of the owner (docs/SPEC.md §40): two optional values kept in the settings and the figures of the month against
 * them, recomputed from the local archive after the login, after a synchronization and after a change of the goals. A failed read
 * of the trips leaves the goals and hides the figures; [reset] on logout (the goals themselves belong to the phone and stay).
 */
class EcoGoalsController(
    private val readGoals: () -> EcoGoals,
    private val saveGoals: (EcoGoals) -> Unit,
    private val readTrips: suspend () -> List<StoredTrip>,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
    /** Called after new goals were stored, e.g. to repaint the widget, which shows a line for them. */
    private val onSaved: () -> Unit = {},
) {
    private val ui = MutableStateFlow(EcoGoalsState())
    val state: StateFlow<EcoGoalsState> = ui.asStateFlow()

    private var generation = 0
    private var loadStarted = false

    fun ensureLoaded() {
        if (!loadStarted) refresh()
    }

    fun refresh() {
        loadStarted = true
        val startedIn = generation
        scope.launch {
            val next = try {
                withContext(io) {
                    val goals = readGoals()
                    val trips = readTrips()
                    EcoGoalsState(goals, ecoProgress(goals, trips, zone(), today()), ecoHistory(goals, trips, zone(), today()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val goals = try {
                    withContext(io) { readGoals() }
                } catch (inner: CancellationException) {
                    throw inner
                } catch (inner: Exception) {
                    EcoGoals()
                }
                EcoGoalsState(goals, null)
            }
            if (generation == startedIn) ui.value = next
        }
    }

    /**
     * Saves both typed values; returns false and changes nothing when either is invalid. An empty field removes its goal. The
     * write is on the caller's thread like the other settings and is a single `SharedPreferences` edit; the figures follow
     * from [refresh].
     */
    fun set(consumptionText: String, evText: String): Boolean {
        val consumption = when (val check = parseDeclaredConsumption(consumptionText)) {
            is DeclaredCheck.Valid -> check.value
            DeclaredCheck.Empty -> null
            DeclaredCheck.Invalid -> return false
        }
        val ev = when (val check = parseEvGoal(evText)) {
            is EvGoalCheck.Valid -> check.pct
            EvGoalCheck.Empty -> null
            EvGoalCheck.Invalid -> return false
        }
        // A goal that changed starts its streak in the month of the change; an unchanged one keeps its month.
        val old = readGoals()
        val month = today().let { MonthKey(it.year, it.monthValue) }
        val goals = EcoGoals(
            maxConsumption = consumption,
            minEvSharePct = ev,
            consumptionSince = when {
                consumption == null -> null
                sameFigure(consumption, old.maxConsumption) -> old.consumptionSince
                else -> month
            },
            evSince = when {
                ev == null -> null
                ev == old.minEvSharePct -> old.evSince
                else -> month
            },
        )
        saveGoals(goals)
        ui.value = ui.value.copy(goals = goals, progress = null, history = null)
        refresh()
        onSaved()
        return true
    }

    fun reset() {
        generation++
        loadStarted = false
        ui.value = EcoGoalsState()
    }
}
