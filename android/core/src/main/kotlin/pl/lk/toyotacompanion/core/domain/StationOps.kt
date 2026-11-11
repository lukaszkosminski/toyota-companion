package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

/** Longest station name kept; longer text is cut, never refused (PRD F57, docs/SPEC.md §57). */
const val STATION_MAX_LENGTH = 60

/** How many recent stations the form and the prompt suggest (PRD F57). */
const val STATION_SUGGESTIONS = 6

/** Closed full-tank intervals a station needs before its consumption is shown (PRD F57). */
const val STATION_MIN_INTERVALS = 3

private val WHITESPACE = Regex("\\s+")
private val HUNDRED = BigDecimal(100)
private val ZERO = BigDecimal(0)

/**
 * One station as the "Dziennik tankowań" shows it (PRD F57, docs/SPEC.md §57).
 *
 * [lPer100Km] is the pooled `sum(liters) / sum(distance) * 100` of the [intervals]
 * closed `OK` intervals whose *opening* full refuel belongs to this station. It is
 * null until [STATION_MIN_INTERVALS] such intervals exist: a smaller sample is not a
 * measurement. [avgPricePerLiter] is `sum(totalCost) / sum(liters)` over the
 * confirmed refuels that carry both values, and is shown from the first one.
 */
data class StationSummary(
    val name: String,
    val refuels: Int,
    val pricedRefuels: Int,
    val avgPricePerLiter: BigDecimal?,
    val intervals: Int,
    val distanceKm: BigDecimal,
    val lPer100Km: BigDecimal?,
)

private data class Accumulator(
    var name: String,
    var refuels: Int = 0,
    var pricedRefuels: Int = 0,
    var pricedLiters: BigDecimal = ZERO,
    var pricedTotal: BigDecimal = ZERO,
    var intervals: Int = 0,
    var intervalLiters: BigDecimal = ZERO,
    var intervalDistance: BigDecimal = ZERO,
)

/**
 * Trim, collapse runs of whitespace to one space, turn an empty text into null and cut
 * to [STATION_MAX_LENGTH] characters. Two names equal after this (ignoring case) are one
 * station; the spelling shown is the one of the newest refuel (PRD F57).
 */
fun normalizeStation(text: String?): String? {
    val collapsed = text?.trim()?.replace(WHITESPACE, " ") ?: return null
    if (collapsed.isEmpty()) return null
    return collapsed.take(STATION_MAX_LENGTH).trimEnd()
}

private fun stationKey(name: String): String = name.lowercase(Locale.ROOT)

/**
 * Stations of [refuels] with their counts, weighted average price and pooled
 * consumption. Only confirmed refuels count; a refuel without a station is not a row
 * (the screen counts those separately). Mirrors Python `station_summaries`.
 */
fun stationSummaries(
    refuels: List<Refuel>,
    intervals: List<EconomyInterval>,
    minIntervals: Int = STATION_MIN_INTERVALS,
): List<StationSummary> {
    val confirmed = refuels
        .filter { it.status == RefuelStatus.CONFIRMED }
        .sortedWith(compareBy({ it.ts }, { it.id }))
    val byKey = LinkedHashMap<String, Accumulator>()
    for (refuel in confirmed) {
        val name = normalizeStation(refuel.station) ?: continue
        val entry = byKey.getOrPut(stationKey(name)) { Accumulator(name) }
        // Oldest first, so the last spelling of the name wins.
        entry.name = name
        entry.refuels++
        val liters = refuel.liters
        val total = refuel.totalCost
        if (liters != null && liters > ZERO && total != null) {
            entry.pricedRefuels++
            entry.pricedLiters = entry.pricedLiters.add(liters)
            entry.pricedTotal = entry.pricedTotal.add(total)
        }
    }

    val stationOf = confirmed.associate { it.id to normalizeStation(it.station)?.let(::stationKey) }
    for (interval in intervals) {
        if (interval.status != EconomyStatus.OK || interval.endRefuelId == null) continue
        val entry = byKey[stationOf[interval.startRefuelId]] ?: continue
        val liters = interval.actualLiters ?: continue
        entry.intervals++
        entry.intervalLiters = entry.intervalLiters.add(liters)
        entry.intervalDistance = entry.intervalDistance.add(interval.distanceKm)
    }

    return byKey.values.map { entry ->
        val average = if (entry.pricedLiters > ZERO) {
            roundPrice(entry.pricedTotal.divideLikePythonDecimal(entry.pricedLiters))
        } else {
            null
        }
        val consumption = if (entry.intervals >= minIntervals && entry.intervalDistance > ZERO) {
            roundConsumption(
                entry.intervalLiters.multiply(HUNDRED).divideLikePythonDecimal(entry.intervalDistance),
            )
        } else {
            null
        }
        StationSummary(
            name = entry.name,
            refuels = entry.refuels,
            pricedRefuels = entry.pricedRefuels,
            avgPricePerLiter = average,
            intervals = entry.intervals,
            distanceKm = roundDistance(entry.intervalDistance),
            lPer100Km = consumption,
        )
    }.sortedWith(
        compareByDescending<StationSummary> { it.refuels }
            .thenBy { stationKey(it.name) },
    )
}

private data class Suggestion(
    var name: String,
    var count: Int = 0,
    var newest: Instant,
)

/**
 * Station names used by [refuels], most often first, then the most recent; at most
 * [limit]. Mirrors Python `station_suggestions`.
 */
fun stationSuggestions(refuels: List<Refuel>, limit: Int = STATION_SUGGESTIONS): List<String> {
    if (limit <= 0) return emptyList()
    val confirmed = refuels
        .filter { it.status == RefuelStatus.CONFIRMED }
        .sortedWith(compareBy({ it.ts }, { it.id }))
    val byKey = LinkedHashMap<String, Suggestion>()
    for (refuel in confirmed) {
        val name = normalizeStation(refuel.station) ?: continue
        val entry = byKey.getOrPut(stationKey(name)) { Suggestion(name, newest = refuel.ts) }
        entry.name = name
        entry.count++
        entry.newest = refuel.ts
    }
    return byKey.values
        .sortedWith(
            compareByDescending<Suggestion> { it.count }
                .thenByDescending { it.newest }
                .thenBy { stationKey(it.name) },
        )
        .take(limit)
        .map { it.name }
}
