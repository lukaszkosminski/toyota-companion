package pl.lk.toyotacompanion.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
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

enum class TripMapEnd { START, END }

/** One button of the trip window: which end and the rounded `geo:` link. */
data class TripMapLink(val end: TripMapEnd, val uri: String)

/**
 * What the window of a tapped trip offers (docs/SPEC.md §10.3): only the ends that have a usable position, start first.
 * An empty list means the row is not clickable.
 */
fun tripMapLinks(record: TripRecord): List<TripMapLink> = listOfNotNull(
    ParkingFormatting.mapUri(record.startLat, record.startLon)?.let { TripMapLink(TripMapEnd.START, it) },
    ParkingFormatting.mapUri(record.endLat, record.endLon)?.let { TripMapLink(TripMapEnd.END, it) },
)
