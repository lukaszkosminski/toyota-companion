package pl.lk.toyotacompanion.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime notification permission of API 33+ and the rule for asking once
 * (docs/SPEC.md §13.2). Below API 33 the permission does not exist, so the channel
 * alone decides whether a notification pops up.
 */

/** From this API level up, posting a notification needs `POST_NOTIFICATIONS`. */
const val NOTIFICATION_PERMISSION_MIN_SDK: Int = 33

/**
 * Whether [sdkInt] needs the permission, it is not granted yet and the user has
 * never been asked. Pure so the "ask exactly once" rule is testable without an
 * Activity; the "asked before" flag lives in `SettingsStore`.
 */
fun shouldAskNotificationPermission(sdkInt: Int, granted: Boolean, askedBefore: Boolean): Boolean =
    sdkInt >= NOTIFICATION_PERMISSION_MIN_SDK && !granted && !askedBefore

/** True when notifications can be posted: below API 33 always, above only with the permission. */
fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < NOTIFICATION_PERMISSION_MIN_SDK ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
