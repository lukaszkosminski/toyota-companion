package pl.lk.toyotacompanion.notify

import kotlin.test.assertEquals
import org.junit.Test

/**
 * The "ask once" rule of the notification permission (docs/SPEC.md §13.2). The
 * rule itself is pure, so the SDK gate and the "already asked" flag are checked
 * without an Activity.
 */
class NotificationPermissionTest {

    @Test
    fun `on api 33 and up an unasked permission is requested once`() {
        assertEquals(true, shouldAskNotificationPermission(sdkInt = 33, granted = false, askedBefore = false))
        assertEquals(false, shouldAskNotificationPermission(sdkInt = 33, granted = false, askedBefore = true))
        assertEquals(false, shouldAskNotificationPermission(sdkInt = 33, granted = true, askedBefore = false))
    }

    @Test
    fun `below api 33 there is nothing to ask about`() {
        assertEquals(false, shouldAskNotificationPermission(sdkInt = 29, granted = false, askedBefore = false))
        assertEquals(NOTIFICATION_PERMISSION_MIN_SDK, 33)
    }
}
