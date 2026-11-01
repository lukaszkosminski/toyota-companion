package pl.lk.toyotacompanion.ui

import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.WeatherRow
import pl.lk.toyotacompanion.core.domain.DayWeather
import pl.lk.toyotacompanion.core.domain.toBigDecimal
import pl.lk.toyotacompanion.core.weather.OpenMeteoClient

/** Most places one tap on "Pobierz pogodę" asks for; the rest waits for the next tap (docs/SPEC.md §23). */
const val WEATHER_MAX_PLACES_PER_FETCH = 10

/** The newest days still lack reanalysis values; a day this recent with no temperature is asked for again. */
const val WEATHER_REANALYSIS_LAG_DAYS = 10L

/** A place as the weather cache and the request know it: the start point rounded to 0.01°, as text. */
data class WeatherPlace(val latitude: String, val longitude: String)

/** One request to the weather service: a place and the days (inclusive) its trips still need. */
data class WeatherRequest(val place: WeatherPlace, val start: LocalDate, val end: LocalDate, val trips: Int)

/** What a tap on "Pobierz pogodę" would do now: the requests of this tap and how many places wait. */
data class WeatherPlan(val requests: List<WeatherRequest>, val placesLeft: Int)

/** The rounded start point of a trip, or `null` without a usable one (docs/SPEC.md §23, D2 and D3). */
fun weatherPlaceOf(record: TripRecord): WeatherPlace? {
    val lat = record.startLat ?: return null
    val lon = record.startLon ?: return null
    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return WeatherPlace(OpenMeteoClient.rounded(lat), OpenMeteoClient.rounded(lon))
}

/** The local date a trip started on, in [zone]; the weather of that day at the start point belongs to it. */
fun weatherDateOf(stored: StoredTrip, zone: ZoneId): LocalDate = stored.record.trip.start.atZone(zone).toLocalDate()

private data class Key(val place: WeatherPlace, val date: LocalDate)

private fun index(rows: List<WeatherRow>): Map<Key, WeatherRow> =
    rows.associateBy { Key(WeatherPlace(it.latitude, it.longitude), it.day.date) }

/**
 * The weather of every trip that has a cached row for its start point and day, by trip id, in the units
 * the domain wants. A trip without a position or without a row is simply absent: the domain counts it as
 * "bez pogody". The domain never sees a coordinate.
 */
fun tripWeather(trips: List<StoredTrip>, rows: List<WeatherRow>, zone: ZoneId): Map<String, DayWeather> {
    val byKey = index(rows)
    val result = HashMap<String, DayWeather>()
    for (stored in trips) {
        val place = weatherPlaceOf(stored.record) ?: continue
        val day = byKey[Key(place, weatherDateOf(stored, zone))]?.day ?: continue
        result[stored.record.id] = DayWeather(
            tempMean = day.tempMeanC?.let { toBigDecimal(it) },
            tempMin = day.tempMinC?.let { toBigDecimal(it) },
            tempMax = day.tempMaxC?.let { toBigDecimal(it) },
            precipMm = day.precipitationMm?.let { toBigDecimal(it) },
            precipHours = day.precipitationHours?.let { toBigDecimal(it) },
            windMaxKmh = day.windMaxKmh?.let { toBigDecimal(it) },
        )
    }
    return result
}

/**
 * What is still to fetch: a (place, day) that has trips and no cached row, or a row of the last
 * [WEATHER_REANALYSIS_LAG_DAYS] days that came back empty. Days from [today] on cannot be asked for (the
 * archive ends yesterday) and are left out. One request per place covers the first to the last missing day;
 * at most [WEATHER_MAX_PLACES_PER_FETCH] places per tap, the busiest first, ties by the place for a stable order.
 */
fun planWeatherFetch(trips: List<StoredTrip>, rows: List<WeatherRow>, zone: ZoneId, today: LocalDate): WeatherPlan {
    val byKey = index(rows)
    val missing = HashMap<WeatherPlace, MutableList<LocalDate>>()
    for (stored in trips) {
        val place = weatherPlaceOf(stored.record) ?: continue
        val date = weatherDateOf(stored, zone)
        if (!date.isBefore(today)) continue
        val cached = byKey[Key(place, date)]
        val stale = cached != null && cached.day.tempMeanC == null && !date.isBefore(today.minusDays(WEATHER_REANALYSIS_LAG_DAYS))
        if (cached == null || stale) missing.getOrPut(place) { ArrayList() } += date
    }
    val ordered = missing.entries.sortedWith(
        compareByDescending<Map.Entry<WeatherPlace, MutableList<LocalDate>>> { it.value.size }
            .thenBy { it.key.latitude }
            .thenBy { it.key.longitude },
    )
    val requests = ordered.take(WEATHER_MAX_PLACES_PER_FETCH).map { (place, dates) ->
        WeatherRequest(place, dates.min(), dates.max(), dates.size)
    }
    return WeatherPlan(requests, (ordered.size - requests.size).coerceAtLeast(0))
}
