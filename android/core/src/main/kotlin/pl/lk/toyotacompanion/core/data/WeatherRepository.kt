package pl.lk.toyotacompanion.core.data

import java.time.Instant
import java.time.LocalDate
import pl.lk.toyotacompanion.core.weather.WeatherDay

/**
 * One cached day of weather at one rounded place (PRD F15, docs/SPEC.md §23). The place is the position
 * rounded to 0.01° as text ([latitude], [longitude]); the cache holds no VIN, no trip id and no finer
 * position. A day the service had no data for is stored with `null` values, so the screen can say
 * "brak danych" and a later fetch can fill it in.
 */
data class WeatherRow(
    val latitude: String,
    val longitude: String,
    val day: WeatherDay,
    val fetchedAt: Instant,
)

/** Weather cache. Identity is `(latitude, longitude, date)`; saving the same key again replaces the row. */
interface WeatherRepository {
    suspend fun save(rows: List<WeatherRow>)

    /** Every cached row, oldest date first. Small: one row per day and place with a trip. */
    suspend fun all(): List<WeatherRow>

    /** Drops rows of days before [date]; returns how many. */
    suspend fun pruneBefore(date: LocalDate): Int

    suspend fun count(): Int

    suspend fun deleteAll()
}
