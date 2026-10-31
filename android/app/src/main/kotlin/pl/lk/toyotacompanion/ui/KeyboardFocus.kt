package pl.lk.toyotacompanion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Gives the field of [focusRequester] the focus and opens the on-screen keyboard,
 * every time [key] changes (docs/SPEC.md §13.1, §13.3). Used by the refuel prompt
 * and by the price form opened from a notification, where the whole point is that
 * the user can type at once.
 *
 * The frame is awaited first: before the first layout of the field there is no
 * focus owner to take the focus, and `requestFocus()` would fail with
 * `IllegalStateException` and never be retried. The controller is read from the
 * composition, so it belongs to the window the field lives in — the dialog has its
 * own window, and a controller taken from the activity would not reach it.
 *
 * A field detached in that very frame (the dialog closed at once) still throws
 * `IllegalStateException`; the keyboard is a convenience, never a reason to crash.
 */
@Composable
fun showKeyboardOnFocus(focusRequester: FocusRequester, key: Any?) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(key) {
        withFrameNanos { }
        try {
            focusRequester.requestFocus()
            keyboard?.show()
        } catch (e: IllegalStateException) {
            // The field left the composition before it could take the focus.
        }
    }
}
