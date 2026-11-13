package pl.lk.toyotacompanion

import android.app.Application
import androidx.work.Configuration
import pl.lk.toyotacompanion.sync.AppWorkerFactory

class ToyotaApp : Application(), Configuration.Provider {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // The monthly budget is gone (2026-11-13); its channel must not stay in the system settings of old installs.
        getSystemService(android.app.NotificationManager::class.java)?.deleteNotificationChannel("budget")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(AppWorkerFactory { container.syncRunner })
            .build()
}
