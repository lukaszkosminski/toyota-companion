package pl.lk.toyotacompanion.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import pl.lk.toyotacompanion.core.data.TripRecord

/** Longest `geo:` link the app hands over: two coordinates of three decimals twice stay far below it. */
private const val MAX_GEO_URI_LENGTH = 64

/**
 * Hands one `geo:` link (built by [ParkingFormatting.mapUri], already rounded) to a map app. Called only from
 * an explicit tap (docs/SPEC.md §18, §10.3). A link that is not a short `geo:` one is refused, so no caller can
 * pass anything else out of the app. Without a map app, or if the system refuses the intent, it shows a short
 * note instead of closing the app.
 */
fun openInMap(context: Context, uri: String) {
    require(uri.startsWith("geo:") && uri.length <= MAX_GEO_URI_LENGTH) { "Only a short geo: link may leave the app" }
    val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, Texts.PARKING_NO_MAP_APP, Toast.LENGTH_SHORT).show()
    } catch (_: RuntimeException) {
        // SecurityException (blocked by policy) and the like: a refused intent never closes the app.
        Toast.makeText(context, Texts.PARKING_NO_MAP_APP, Toast.LENGTH_SHORT).show()
    }
}

/** The labels a map point can carry, in the order the saved state indexes them. */
val MAP_LABELS: List<String> get() = listOf(Texts.MAP_START, Texts.MAP_END, Texts.MAP_PLACE)

/** A position as the map gets it: rounded to 0.001° (about 100 m), or `null` without a usable one. */
private fun roundedPoint(label: String, lat: Double?, lon: Double?): MapPoint? {
    if (ParkingFormatting.mapUri(lat, lon) == null) return null
    return MapPoint(label, roundedCoordinate(requireNotNull(lat)).toDouble(), roundedCoordinate(requireNotNull(lon)).toDouble())
}

/**
 * The points of a tapped trip's map (docs/SPEC.md §10.3, §26): start and end, only those with a usable position,
 * already rounded. An empty list means the row is not clickable.
 */
fun tripMapPoints(record: TripRecord): List<MapPoint> = listOfNotNull(
    roundedPoint(Texts.MAP_START, record.startLat, record.startLon),
    roundedPoint(Texts.MAP_END, record.endLat, record.endLon),
)

/** The point of a parking place's map, rounded, or an empty list without a position. */
fun placeMapPoints(lat: Double?, lon: Double?): List<MapPoint> = listOfNotNull(roundedPoint(Texts.MAP_PLACE, lat, lon))
