package pl.lk.toyotacompanion.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Hands one `geo:` link (built by [ParkingFormatting.mapUri], already rounded) to a map app. Called only from
 * an explicit tap (docs/SPEC.md §18, §10.3); without a map app it shows a short note instead of failing.
 */
fun openInMap(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, Texts.PARKING_NO_MAP_APP, Toast.LENGTH_SHORT).show()
    }
}
