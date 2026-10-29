package pl.lk.toyotacompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pl.lk.toyotacompanion.ui.AppRoot

/**
 * Single activity: the login form or the synchronization status, decided by
 * [pl.lk.toyotacompanion.ui.SessionController] (docs/SPEC.md §9).
 *
 * Edge-to-edge: the content draws behind the system bars, whose backgrounds are
 * transparent (res/values{,-night}/themes.xml); every screen pads itself with the
 * system-bar insets from Scaffold.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = (application as ToyotaApp).container.session
        session.start()
        setContent { AppRoot(session) }
    }
}
