package pl.lk.toyotacompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import pl.lk.toyotacompanion.ui.AppRoot

/**
 * Single activity: the login form or the synchronization status, decided by
 * [pl.lk.toyotacompanion.ui.SessionController] (docs/SPEC.md §9).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = (application as ToyotaApp).container.session
        session.start()
        setContent { AppRoot(session) }
    }
}
