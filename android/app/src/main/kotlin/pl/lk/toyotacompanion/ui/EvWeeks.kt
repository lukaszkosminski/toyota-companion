package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/** The statistics screen shows at most this many of the newest weeks. */
const val EV_WEEKS_SHOWN = 12

/**
 * One week of driving on the battery (docs/SPEC.md §36): the week starts on Monday ([weekStart], local date of the trip's start).
 * [evKm] counts each trip up to its own distance; [evMinutes] is the sum of Toyota's electric time over the trips that report it,
 * `null` when none does. [evSharePct] is whole percent of [distanceKm].
 */
data class EvWeek(
    val weekStart: LocalDate,
    val trips: Int,
    val distanceKm: BigDecimal,
    val evKm: BigDecimal,
    val evSharePct: BigDecimal,
    val evMinutes: Long?,
)

/**
 * The newest [limit] weeks that have trips, oldest first. Only trips with a positive finite distance count; a trip without
 * electric data adds its kilometres to the week and nothing to the electric part, so a week of such trips shows 0 %, not a gap.
 * The Toyota hybrid reports no battery charge level (docs/SPEC.md §36), so this is the picture of the battery the data allows.
 */
fun evByWeek(trips: List<StoredTrip>, zone: ZoneId, limit: Int = EV_WEEKS_SHOWN): List<EvWeek> {
    val usable = trips.filter { it.record.trip.distanceKm.isFinite() && it.record.trip.distanceKm > 0.0 }
    val byWeek = usable.groupBy {
        it.record.trip.start.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    return byWeek.entries.sortedBy { it.key }.map { (week, group) ->
        var km = BigDecimal.ZERO
        var ev = BigDecimal.ZERO
        var seconds = 0L
        var anyTime = false
        for (stored in group) {
            val trip = stored.record.trip
            km = km.add(toBigDecimal(trip.distanceKm))
            val evKm = trip.evDistanceKm
            if (evKm != null && evKm.isFinite() && evKm > 0.0) ev = ev.add(toBigDecimal(minOf(evKm, trip.distanceKm)))
            stored.record.hdcEvTimeS?.takeIf { it >= 0 }?.let {
                seconds += it
                anyTime = true
            }
        }
        EvWeek(
            weekStart = week,
            trips = group.size,
            distanceKm = km.setScale(1, RoundingMode.HALF_UP),
            evKm = ev.setScale(1, RoundingMode.HALF_UP),
            evSharePct = ev.multiply(BigDecimal(100)).divide(km, 0, RoundingMode.HALF_UP),
            evMinutes = if (anyTime) seconds / 60 else null,
        )
    }.takeLast(limit)
}

/** Polish text of the weekly electric driving (docs/SPEC.md §36). Pure, independent of `Locale`. */
object EvWeeksFormatting {
    /** `13.10` for the bar label: day and month of the Monday. */
    fun weekLabel(week: EvWeek): String =
        week.weekStart.dayOfMonth.toString().padStart(2, '0') + "." + week.weekStart.monthValue.toString().padStart(2, '0')

    /** `13.10: 62 % na prądzie · 41,2 z 66,5 km · 98 min · trasy: 9`. */
    fun line(week: EvWeek): String = listOfNotNull(
        weekLabel(week) + ": " + Texts.evOnBattery(TripsFormatting.number(week.evSharePct, 0)),
        TripsFormatting.number(week.evKm, 1) + Lang.pick(" z ", " of ") + TripsFormatting.number(week.distanceKm, 1) + " km",
        week.evMinutes?.let { Texts.evMinutes(it) },
        Texts.statsTrips(week.trips),
    ).joinToString(Texts.SEPARATOR)
}
