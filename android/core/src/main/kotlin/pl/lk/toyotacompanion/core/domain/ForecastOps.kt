package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/** Below this share of the tank the forecast says "refuel". Mirrors Python `RESERVE_PCT`. */
val RESERVE_PCT: BigDecimal = BigDecimal("10")

/** Days of trips that define the usual consumption and daily distance. Mirrors Python `WINDOW_DAYS`. */
const val WINDOW_DAYS: Int = 28

private val HUNDRED = BigDecimal(100)
private val ZERO = BigDecimal(0)

/**
 * Forecast of ONE vehicle from its latest fuel reading and its trips (PRD F14, docs/SPEC.md §20).
 * Fuel now = `level% x capacity` minus the fuel of the trips that started at or after the
 * reading; consumption and daily distance from the trips of the last [windowDays] days (a
 * younger archive uses the days it has, at least one). Toyota's own range is not used - it
 * proved unreliable. `null` without a reading, without trips in the window or with zero
 * distance or fuel there. Mirrors Python `refuel_forecast`.
 */
fun refuelForecast(
    reading: FuelReading?,
    trips: List<Trip>,
    now: Instant,
    capacityL: BigDecimal = DEFAULT_TANK_CAPACITY_L,
    reservePct: BigDecimal = RESERVE_PCT,
    windowDays: Int = WINDOW_DAYS,
): RefuelForecast? {
    if (reading == null) return null
    val windowStart = now.minus(Duration.ofDays(windowDays.toLong()))
    val inWindow = trips.filter { !it.start.isBefore(windowStart) && !it.start.isAfter(now) }
    val distance = inWindow.fold(ZERO) { acc, trip -> acc.add(toBigDecimal(trip.distanceKm)) }
    val fuel = inWindow.fold(ZERO) { acc, trip -> acc.add(trip.fuelL) }
    if (distance <= ZERO || fuel <= ZERO) return null

    val firstStart = trips.minOf { it.start }
    val archiveDays = Duration.between(firstStart, now).seconds / 86_400.0
    val days = BigDecimal(ceil(archiveDays).toInt().coerceIn(1, windowDays))

    val usedSince = trips.filter { !it.start.isBefore(reading.ts) }.fold(ZERO) { acc, trip -> acc.add(trip.fuelL) }
    val left = BigDecimal(reading.levelPct).divideLikePythonDecimal(HUNDRED).multiply(capacityL).subtract(usedSince).max(ZERO)
    val consumption = fuel.multiply(HUNDRED).divideLikePythonDecimal(distance)
    val daily = distance.divideLikePythonDecimal(days)
    val reserve = reservePct.divideLikePythonDecimal(HUNDRED).multiply(capacityL)
    val kmToReserve = left.subtract(reserve).max(ZERO).multiply(HUNDRED).divideLikePythonDecimal(consumption)
    return RefuelForecast(
        litersLeft = roundLiters(left),
        levelPct = roundPercent(left.multiply(HUNDRED).divideLikePythonDecimal(capacityL)),
        lPer100Km = roundConsumption(consumption),
        dailyKm = roundDistance(daily),
        kmToReserve = roundDistance(kmToReserve),
        daysToReserve = kmToReserve.divideLikePythonDecimal(daily).setScale(1, RoundingMode.HALF_UP),
    )
}
