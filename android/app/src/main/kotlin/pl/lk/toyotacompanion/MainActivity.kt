package pl.lk.toyotacompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pl.lk.toyotacompanion.ui.AppRoot

/**
 * Single activity: the login form, the synchronization status or the trip list,
 * decided by [pl.lk.toyotacompanion.ui.SessionController] and by the screen the
 * user picked in [pl.lk.toyotacompanion.ui.AppRoot] (docs/SPEC.md §9, §10.3).
 *
 * Edge-to-edge: the content draws behind the system bars, whose backgrounds are
 * transparent (res/values{,-night}/themes.xml); every screen pads itself with the
 * system-bar insets from Scaffold.
 *
 * `launchMode="singleTop"` (AndroidManifest): tapping the notification of a refuel
 * while the app is already open must not start a second activity — the id arrives
 * in [onNewIntent] and [AppRoot] opens the price form of that entry
 * (docs/SPEC.md §13.1).
 */
class MainActivity : ComponentActivity() {
    /**
     * Refuel id from the notification, held as Compose state so the composition
     * sees it. Read once per intent: a rotation calls `onCreate` again with the
     * same intent, and replaying the deep link would reopen the form the user has
     * already left (docs/SPEC.md §13.1).
     */
    private var refuelDeepLinkId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) refuelDeepLinkId = intent?.getStringExtra(EXTRA_REFUEL_ID)
        val container = (application as ToyotaApp).container
        container.session.start()
        setContent {
            AppRoot(
                container.session,
                container.tripsLoader,
                container.refuelController,
                container.defaultPriceController,
                container.refuelPromptController,
                askNotificationPermission = container.askNotificationPermission,
                deepLinkRefuelId = refuelDeepLinkId,
                onDeepLinkShown = { refuelDeepLinkId = null },
            )
        }
    }

    /** The app was already running: the notification of [EXTRA_REFUEL_ID] asks for the price form. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refuelDeepLinkId = intent.getStringExtra(EXTRA_REFUEL_ID)
    }

    companion object {
        /** Extra of the notification tap: which refuel to open (docs/SPEC.md §13.1). */
        const val EXTRA_REFUEL_ID = "refuel_id"
    }
}
