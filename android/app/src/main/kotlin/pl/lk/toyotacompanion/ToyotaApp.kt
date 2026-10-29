package pl.lk.toyotacompanion

import android.app.Application
import androidx.work.Configuration
import pl.lk.toyotacompanion.sync.AppWorkerFactory

class ToyotaApp : Application(), Configuration.Provider {
    val container: AppContainer by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(AppWorkerFactory { container.syncRunner })
            .build()
}
