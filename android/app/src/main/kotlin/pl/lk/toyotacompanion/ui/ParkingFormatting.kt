package pl.lk.toyotacompanion.ui

import java.time.Duration
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip

/**
 * Polish text of the parking diary (PRD F17, docs/SPEC.md §18). Pure and independent of
 * `Locale`. Positions are only ever shown here, rounded to 0.001° (about 100 m) by
 * [roundedCoordinate] — never logged, exported or handed to another app (docs/SECURITY.md).
 */
object ParkingFormatting {

    /** `2 d 3 h`, `3 h 05 min` or `45 min` — whole minutes, rounded down. */
    fun duration(value: Duration): String {
        val minutes = value.toMinutes().coerceAtLeast(0)
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val rest = minutes % 60
        return when {
            days > 0 -> "$days d $hours h"
            hours > 0 -> "$hours h " + rest.toString().padStart(2, '0') + " min"
            else -> "$rest min"
        }
    }

    /** `54.372, 18.638`, or the "no position" note when a trip had no coordinates. */
    fun place(lat: Double?, lon: Double?): String =
        // A non-finite value (a broken row) is no position, never a crash of the screen.
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) Texts.PARKING_NO_POSITION else roundedCoordinate(lat) + ", " + roundedCoordinate(lon)

    /** One stop: `29.10 14:05 – 30.10 07:40 · 17 h 35 min`. */
    fun stopLine(stop: ParkingStop, zone: ZoneId): String =
        RefuelFormatting.time(stop.from, zone) + " – " + RefuelFormatting.time(stop.to, zone) +
            Texts.SEPARATOR + duration(stop.duration)

    /** One place: `postoje: 12 · razem 3 d 4 h · ostatnio 29.10 14:05`. */
    fun placeLine(place: ParkingPlace, zone: ZoneId): String =
        Texts.parkingStops(place.stops) + Texts.SEPARATOR + Texts.parkingTotal(duration(place.total)) +
            Texts.SEPARATOR + Texts.parkingLast(RefuelFormatting.time(place.lastStop, zone))
}

/** The archived trips behind the parking diary: one vehicle, from `SyncEngine.trips`. */
fun parkingLogOf(stored: List<StoredTrip>): ParkingLog = parkingLog(stored.map { it.record })
