package pl.lk.toyotacompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pl.lk.toyotacompanion.ui.AppRoot

/**
 * Single activity: the login form, the synchronization status or the trip list,
 * decided by [pl.lk.toyotacompanion.ui.SessionController] and by the screen the
 * user picked in [pl.lk.toyotacompanion.ui.AppRoot] (docs/SPEC.md §9, §10.3).
 *
 * Edge-to-edge: the content draws behind the system bars, whose backgrounds are
 * transparent (res/values{,-night}/themes.xml); every screen pads itself with the
 * system-bar insets from Scaffold.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ToyotaApp).container
        container.session.start()
        setContent { AppRoot(
                container.session,
                container.tripsLoader,
                container.refuelController,
                container.defaultPriceController,
            ) }
    }
}
