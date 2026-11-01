package pl.lk.toyotacompanion.ui

import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.WeatherRow
import pl.lk.toyotacompanion.core.domain.DayWeather
import pl.lk.toyotacompanion.core.domain.toBigDecimal
import pl.lk.toyotacompanion.core.weather.WeatherDay
import pl.lk.toyotacompanion.core.weather.OpenMeteoClient

/** Most requests one automatic run makes (after a synchronization or when the screen opens); the first backfill fits in one. */
const val WEATHER_MAX_REQUESTS_PER_AUTO_FETCH = 100

/** Most requests one tap on "Pobierz pogodę" makes; the rest waits for the next tap (docs/SPEC.md §23). */
const val WEATHER_MAX_REQUESTS_PER_FETCH = 10

/** Missing days of a place closer than this many days share one request; farther runs are asked for separately. */
const val WEATHER_MERGE_GAP_DAYS = 14L

/** The service started to be asked for `weather_code` at this moment; older cached days are fetched once more for the icon. */
val WEATHER_CODE_SINCE: java.time.Instant = java.time.Instant.parse("2026-11-01T18:00:00Z")

/** The newest days still lack reanalysis values; a day this recent with no temperature is asked for again. */
const val WEATHER_REANALYSIS_LAG_DAYS = 10L

/** A place as the weather cache and the request know it: the start point rounded to 0.01°, as text. */
data class WeatherPlace(val latitude: String, val longitude: String)

/** One request to the weather service: a place and a run of days (inclusive) its trips still need. */
data class WeatherRequest(val place: WeatherPlace, val start: LocalDate, val end: LocalDate, val trips: Int)

/** What a tap on "Pobierz pogodę" would do now: the requests of this tap and how many requests wait for the next. */
data class WeatherPlan(val requests: List<WeatherRequest>, val requestsLeft: Int)

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

/** The cached day of every trip that has one, by trip id, as the service gave it (the trips list shows it). */
fun tripWeatherDays(trips: List<StoredTrip>, rows: List<WeatherRow>, zone: ZoneId): Map<String, WeatherDay> {
    val byKey = index(rows)
    val result = HashMap<String, WeatherDay>()
    for (stored in trips) {
        val place = weatherPlaceOf(stored.record) ?: continue
        val day = byKey[Key(place, weatherDateOf(stored, zone))]?.day ?: continue
        result[stored.record.id] = day
    }
    return result
}

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
 * [WEATHER_REANALYSIS_LAG_DAYS] days that came back without a temperature or a precipitation. Days from [today]
 * on cannot be asked for (the archive ends yesterday) and are left out. The missing days of a place are split
 * into runs: days closer than [WEATHER_MERGE_GAP_DAYS] share a request, so a place visited two years ago and
 * yesterday is not asked for the whole span. At most [WEATHER_MAX_REQUESTS_PER_FETCH] requests per tap, the ones
 * serving the most trips first, ties by place and start for a stable order. [today] is the local date of [zone],
 * the same clock that dates the trips.
 */
fun planWeatherFetch(
    trips: List<StoredTrip>,
    rows: List<WeatherRow>,
    zone: ZoneId,
    today: LocalDate,
    maxRequests: Int = WEATHER_MAX_REQUESTS_PER_FETCH,
): WeatherPlan {
    val byKey = index(rows)
    // One entry per trip (a trip counts once even when its day is shared), so the run size is a trip count.
    val missing = HashMap<WeatherPlace, MutableList<LocalDate>>()
    for (stored in trips) {
        val place = weatherPlaceOf(stored.record) ?: continue
        val date = weatherDateOf(stored, zone)
        if (!date.isBefore(today)) continue
        val cached = byKey[Key(place, date)]
        val recent = !date.isBefore(today.minusDays(WEATHER_REANALYSIS_LAG_DAYS))
        val gap = cached != null && (cached.day.tempMeanC == null || cached.day.precipitationMm == null)
        // Rows fetched before the weather code was asked for have a temperature but no code: fetched once more.
        val noCode = cached != null && cached.day.weatherCode == null && cached.day.tempMeanC != null &&
            cached.fetchedAt.isBefore(WEATHER_CODE_SINCE)
        if (cached == null || (gap && recent) || noCode) missing.getOrPut(place) { ArrayList() } += date
    }
    val all = ArrayList<WeatherRequest>()
    for ((place, dates) in missing) {
        val sorted = dates.sorted()
        var from = sorted.first()
        var last = from
        var count = 0
        for (date in sorted) {
            if (date.isAfter(last.plusDays(WEATHER_MERGE_GAP_DAYS))) {
                all += WeatherRequest(place, from, last, count)
                from = date
                count = 0
            }
            last = date
            count++
        }
        all += WeatherRequest(place, from, last, count)
    }
    val ordered = all.sortedWith(
        compareByDescending<WeatherRequest> { it.trips }
            .thenBy { it.place.latitude }
            .thenBy { it.place.longitude }
            .thenBy { it.start },
    )
    val requests = ordered.take(maxRequests)
    return WeatherPlan(requests, ordered.size - requests.size)
}
