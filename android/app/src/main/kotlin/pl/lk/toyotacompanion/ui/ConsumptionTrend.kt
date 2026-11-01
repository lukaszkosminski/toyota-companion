package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/** A month needs this many kilometres to count: a few short trips say more about the trips than about the car. */
const val CONSUMPTION_MIN_MONTH_KM = 50.0

/** The chart shows at most this many of the newest months. */
const val CONSUMPTION_CHART_MONTHS = 12

/** Fewer months than this give no trend. */
const val CONSUMPTION_MIN_TREND_MONTHS = 3

/** A change over the span below this share of the mean is "stable". */
val CONSUMPTION_STABLE_BELOW_PCT: BigDecimal = BigDecimal(5)

enum class ConsumptionTrend { RISING, FALLING, STEADY }

/** One bar of the chart: a month's average consumption, l/100 km = Σ fuel / Σ km (0.01). */
data class MonthConsumption(val month: MonthKey, val trips: Int, val distanceKm: BigDecimal, val lPer100: BigDecimal)

/**
 * The status-screen numbers (docs/SPEC.md §24): the average of the whole archive, the bars of the newest months and
 * the direction they take. [trend] and [changePct] are `null` with fewer than [CONSUMPTION_MIN_TREND_MONTHS] months.
 */
data class ConsumptionView(
    val overall: BigDecimal?,
    val overallKm: BigDecimal,
    val months: List<MonthConsumption>,
    val trend: ConsumptionTrend?,
    val changePct: BigDecimal?,
)

private fun ordinal(month: MonthKey): Int = month.year * 12 + (month.month - 1)

/**
 * Whole-period and monthly average consumption of the stored trips, months in the zone of the trips list. Trips with a
 * non-finite or non-positive distance or a negative fuel reading are left out. Months under
 * [CONSUMPTION_MIN_MONTH_KM] are not bars. The trend is the least-squares line through the bars: its rise over the
 * span against the mean of the bars, [CONSUMPTION_STABLE_BELOW_PCT] percent or less being STEADY. The regression is
 * only a classifier of the picture, so it runs on doubles; every shown number is a rounded BigDecimal.
 */
fun consumptionView(trips: List<StoredTrip>, zone: ZoneId): ConsumptionView {
    val usable = trips.filter { stored ->
        val trip = stored.record.trip
        trip.distanceKm.isFinite() && trip.distanceKm > 0.0 && trip.fuelL.signum() >= 0
    }
    var totalKm = BigDecimal.ZERO
    var totalFuel = BigDecimal.ZERO
    for (stored in usable) {
        totalKm = totalKm.add(toBigDecimal(stored.record.trip.distanceKm))
        totalFuel = totalFuel.add(stored.record.trip.fuelL)
    }
    val overall = if (totalKm.signum() > 0) totalFuel.multiply(HUNDRED).divide(totalKm, 2, RoundingMode.HALF_UP) else null

    val byMonth = usable.groupBy { stored ->
        val day = stored.record.trip.start.atZone(zone)
        MonthKey(day.year, day.monthValue)
    }
    val bars = byMonth.entries.sortedBy { it.key }.mapNotNull { (month, group) ->
        var km = BigDecimal.ZERO
        var fuel = BigDecimal.ZERO
        for (stored in group) {
            km = km.add(toBigDecimal(stored.record.trip.distanceKm))
            fuel = fuel.add(stored.record.trip.fuelL)
        }
        if (km.toDouble() < CONSUMPTION_MIN_MONTH_KM) return@mapNotNull null
        MonthConsumption(month, group.size, km.setScale(1, RoundingMode.HALF_UP), fuel.multiply(HUNDRED).divide(km, 2, RoundingMode.HALF_UP))
    }.takeLast(CONSUMPTION_CHART_MONTHS)

    val (trend, change) = trendOf(bars)
    return ConsumptionView(overall, totalKm.setScale(1, RoundingMode.HALF_UP), bars, trend, change)
}

private fun trendOf(bars: List<MonthConsumption>): Pair<ConsumptionTrend?, BigDecimal?> {
    if (bars.size < CONSUMPTION_MIN_TREND_MONTHS) return null to null
    val xs = bars.map { ordinal(it.month).toDouble() }
    val ys = bars.map { it.lPer100.toDouble() }
    val meanX = xs.average()
    val meanY = ys.average()
    if (meanY <= 0.0) return null to null
    var num = 0.0
    var den = 0.0
    for (i in xs.indices) {
        num += (xs[i] - meanX) * (ys[i] - meanY)
        den += (xs[i] - meanX) * (xs[i] - meanX)
    }
    if (den == 0.0) return null to null
    val rise = num / den * (xs.last() - xs.first())
    val pct = BigDecimal(rise / meanY * 100.0).setScale(1, RoundingMode.HALF_UP)
    val trend = when {
        pct.abs() <= CONSUMPTION_STABLE_BELOW_PCT -> ConsumptionTrend.STEADY
        pct.signum() > 0 -> ConsumptionTrend.RISING
        else -> ConsumptionTrend.FALLING
    }
    return trend to pct
}

private val HUNDRED = BigDecimal(100)
