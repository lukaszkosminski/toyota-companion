package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import pl.lk.toyotacompanion.core.data.TripRecord

/**
 * Parking diary (docs/PRD.md F17): where the car stood between two archived trips.
 *
 * Everything here is a pure function over trips already in the archive. No location is ever
 * requested from Toyota - such a call would need `refresh_status()`, which drains the 12 V
 * battery and is forbidden (AGENTS.md, docs/SECURITY.md). Coordinates stay on this screen:
 * they are never logged, never written to the CSV sheet and never handed to another app, and
 * the screen shows them through [roundedCoordinate] (about 100 m), the coarsest form that
 * still tells "home" from "work" (docs/SECURITY.md).
 */

/** Two parked positions closer than this are the same place (a car park is bigger than a point). */
const val PARKING_MATCH_M: Double = 150.0

/** Shorter than this the car was not parked, it was a stopover in a journey. */
val MIN_PARK: Duration = Duration.ofMinutes(30)

/**
 * A gap longer than three weeks is a hole in the archive (the car was away, or Toyota had not
 * synced), not a place the user could recognise, so it is counted instead of listed.
 */
val MAX_CLAIM: Duration = Duration.ofDays(21)

/** One parked car: from the end of one trip to the start of the next. */
data class ParkingStop(
    val from: Instant,
    val to: Instant,
    val lat: Double?,
    val lon: Double?,
) {
    val duration: Duration get() = Duration.between(from, to)
}

/** A place the car returned to. Its centre is the position of its first stop. */
data class ParkingPlace(
    val lat: Double,
    val lon: Double,
    val stops: Int,
    val total: Duration,
    val lastStop: Instant,
)

/** The whole diary of one vehicle: stops newest first, plus the counters of what was skipped. */
data class ParkingLog(
    val stops: List<ParkingStop>,
    val places: List<ParkingPlace>,
    val unmatched: Int,
    val longGaps: Int,
)

/** Mean-earth radius used for the distances; 150 m decisions do not need a geoid. */
private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance in metres (haversine). */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val h = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/**
 * Coordinate with three decimals, i.e. about 100 m - the form the screen may show
 * (docs/SECURITY.md). HALF_UP, written with a dot because a coordinate is not a number
 * a reader parses; `TripsFormatting` keeps the comma for money and distances.
 */
fun roundedCoordinate(value: Double): String =
    BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).toPlainString()

/**
 * Parking diary of ONE vehicle from its archived trips, in any input order.
 *
 * Consecutive trips (N, N+1) after sorting by (start, id) leave a gap `N+1.start - N.end`:
 * longer than [MAX_CLAIM] it is counted in [ParkingLog.longGaps]; shorter than [MIN_PARK] it
 * is skipped silently; further apart than [PARKING_MATCH_M] it is counted in
 * [ParkingLog.unmatched] (a trip is missing from the archive, so we do not know where the car
 * stood). Everything else is a stop at the middle of the two ends; when a trip has no
 * coordinates at all the stop is still listed, just without a position.
 */
fun parkingLog(trips: List<TripRecord>): ParkingLog {
    val ordered = trips.sortedWith(compareBy({ it.trip.start }, { it.id }))
    val stops = ArrayList<ParkingStop>((ordered.size - 1).coerceAtLeast(0))
    var unmatched = 0
    var longGaps = 0
    for (index in 0 until ordered.size - 1) {
        val before = ordered[index]
        val after = ordered[index + 1]
        val gap = Duration.between(before.trip.end, after.trip.start)
        if (gap > MAX_CLAIM) {
            longGaps += 1
            continue
        }
        if (gap < MIN_PARK) continue
        val end = before.endPosition()
        val start = after.startPosition()
        if (end != null && start != null &&
            distanceMeters(end.first, end.second, start.first, start.second) > PARKING_MATCH_M
        ) {
            // The car moved without an archived trip: we cannot say where it stood.
            unmatched += 1
            continue
        }
        // A trip without GPS still leaves a stop; only its position is unknown.
        val position = if (end == null || start == null) {
            null
        } else {
            (end.first + start.first) / 2.0 to (end.second + start.second) / 2.0
        }
        stops += ParkingStop(
            from = before.trip.end,
            to = after.trip.start,
            lat = position?.first,
            lon = position?.second,
        )
    }
    return ParkingLog(
        stops = stops.reversed(),
        places = placeSummary(stops),
        unmatched = unmatched,
        longGaps = longGaps,
    )
}

/**
 * Groups the positioned stops into places, greedily and oldest first: a stop joins the first
 * place whose centre lies within [PARKING_MATCH_M], otherwise it opens one. So "home" is the
 * position of the first time we saw the car there, not a moving average.
 */
private fun placeSummary(stops: List<ParkingStop>): List<ParkingPlace> {
    class Accumulator(val lat: Double, val lon: Double) {
        var count: Int = 0
        var total: Duration = Duration.ZERO
        var lastStop: Instant = Instant.EPOCH

        fun add(stop: ParkingStop) {
            count += 1
            total += stop.duration
            if (stop.from > lastStop) lastStop = stop.from
        }
    }

    val places = ArrayList<Accumulator>()
    for (stop in stops.sortedBy { it.from }) {
        val lat = stop.lat ?: continue
        val lon = stop.lon ?: continue
        val place = places.firstOrNull { distanceMeters(it.lat, it.lon, lat, lon) <= PARKING_MATCH_M }
            ?: Accumulator(lat, lon).also { places += it }
        place.add(stop)
    }
    return places
        .sortedWith(compareByDescending<Accumulator> { it.total }.thenByDescending { it.lastStop })
        .map { ParkingPlace(it.lat, it.lon, it.count, it.total, it.lastStop) }
}

private fun TripRecord.endPosition(): Pair<Double, Double>? =
    endLat?.let { lat -> endLon?.let { lon -> lat to lon } }

private fun TripRecord.startPosition(): Pair<Double, Double>? =
    startLat?.let { lat -> startLon?.let { lon -> lat to lon } }
