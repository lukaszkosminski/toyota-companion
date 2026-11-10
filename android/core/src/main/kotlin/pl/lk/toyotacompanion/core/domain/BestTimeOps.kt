package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId

/**
 * The best time to leave (PRD F53, docs/SPEC.md §53): the trips of ONE repeated route, grouped by the day kind and the departure band of
 * their start, with the median drive time and the litres per 100 km of each cell. Mirrors `backend/app/domain/besttime.py` (the same test
 * names live in `backend/tests/test_domain_besttime.py`). The route itself is chosen by the app (`SameRoutes`), so this file takes plain trips.
 */

/** A cell needs this many trips to be shown and to count (docs/SPEC.md §53). */
const val BEST_TIME_MIN_CELL_TRIPS: Int = 3

/** A conclusion needs this many cells with data. */
const val BEST_TIME_MIN_CELLS: Int = 2

/** Two medians closer than this many minutes are "no clear difference". */
const val BEST_TIME_MIN_DIFFERENCE_MINUTES: Long = 2

/** Working days (Monday to Friday) and the weekend, by the day of the start in the zone of the list. */
enum class DayKind { WORKING, WEEKEND }

/** Departure bands by the local hour of the start; the night band runs from 21 to 5 over midnight (one band, as decided). */
enum class DepartureBand { H5_7, H7_9, H9_12, H12_15, H15_18, H18_21, NIGHT }

fun dayKindOf(day: DayOfWeek): DayKind = if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) DayKind.WEEKEND else DayKind.WORKING

/** The band of a local hour 0–23: 5–7, 7–9, 9–12, 12–15, 15–18, 18–21 and the night 21–5 (both sides of midnight). */
fun departureBandOf(hour: Int): DepartureBand = when (hour) {
    in 21..23, in 0..4 -> DepartureBand.NIGHT
    in 5..6 -> DepartureBand.H5_7
    in 7..8 -> DepartureBand.H7_9
    in 9..11 -> DepartureBand.H9_12
    in 12..14 -> DepartureBand.H12_15
    in 15..17 -> DepartureBand.H15_18
    else -> DepartureBand.H18_21
}

/**
 * One cell: [trips] of the route that started on [day] in [band]. [medianMinutes] is the median of the drive times (`end - start`) rounded
 * to a whole minute, HALF_UP; [lPer100Km] is `Σ litres * 100 / Σ km` (0,01), `null` only without any distance.
 */
data class BestTimeCell(
    val day: DayKind,
    val band: DepartureBand,
    val trips: Int,
    val medianMinutes: Long,
    val lPer100Km: BigDecimal?,
)

/**
 * All cells of a route with at least [BEST_TIME_MIN_CELL_TRIPS] trips, in the order of the screen (working days first, then the bands in
 * order). [fastest] and [slowest] are the cells with the lowest and the highest median; they exist only with [BEST_TIME_MIN_CELLS] cells.
 */
data class BestTimeGrid(val trips: Int, val cells: List<BestTimeCell>) {
    val fastest: BestTimeCell? get() = if (cells.size < BEST_TIME_MIN_CELLS) null else cells.minBy { it.medianMinutes }
    val slowest: BestTimeCell? get() = if (cells.size < BEST_TIME_MIN_CELLS) null else cells.maxBy { it.medianMinutes }
}

/**
 * The grid of one route. A trip counts when its drive time is positive, its distance is finite and above zero and its fuel is not negative;
 * a trip with 0 l stays in (driven on the battery, as in F19). The start decides the day and the band: a trip that crosses midnight belongs to
 * the band of its start, and a summer or winter time change is handled by the zone (the hour is the local hour of the start).
 */
fun bestTimeGrid(trips: List<Trip>, zone: ZoneId): BestTimeGrid {
    class Sample(val seconds: Long, val km: BigDecimal, val fuelL: BigDecimal)

    val groups = LinkedHashMap<Pair<DayKind, DepartureBand>, MutableList<Sample>>()
    var counted = 0
    for (trip in trips) {
        val seconds = Duration.between(trip.start, trip.end).seconds
        if (seconds <= 0 || !trip.distanceKm.isFinite() || trip.distanceKm <= 0.0 || trip.fuelL.signum() < 0) continue
        val local = trip.start.atZone(zone)
        val key = dayKindOf(local.dayOfWeek) to departureBandOf(local.hour)
        groups.getOrPut(key) { mutableListOf() } += Sample(seconds, toBigDecimal(trip.distanceKm), trip.fuelL)
        counted++
    }
    val cells = groups.entries
        .filter { it.value.size >= BEST_TIME_MIN_CELL_TRIPS }
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .map { (key, samples) ->
            val km = samples.fold(BigDecimal.ZERO) { sum, s -> sum.add(s.km) }
            val fuel = samples.fold(BigDecimal.ZERO) { sum, s -> sum.add(s.fuelL) }
            BestTimeCell(
                day = key.first,
                band = key.second,
                trips = samples.size,
                medianMinutes = medianMinutes(samples.map { it.seconds }),
                lPer100Km = if (km.signum() > 0) fuel.multiply(BigDecimal(100)).divideLikePythonDecimal(km).setScale(2, RoundingMode.HALF_UP) else null,
            )
        }
    return BestTimeGrid(counted, cells)
}

/** The median of drive times in seconds, as whole minutes: the mean of the two middle values for an even count, then HALF_UP to a minute. */
internal fun medianMinutes(seconds: List<Long>): Long {
    val sorted = seconds.sorted()
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 1) {
        BigDecimal(sorted[middle])
    } else {
        BigDecimal(sorted[middle - 1] + sorted[middle]).divide(BigDecimal(2))
    }
    return median.divide(BigDecimal(60), 0, RoundingMode.HALF_UP).toLong()
}
