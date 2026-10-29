package pl.lk.toyotacompanion.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import pl.lk.toyotacompanion.core.sync.SyncOutcome

/**
 * Background synchronization (docs/SPEC.md §8). Never calls `refresh_status`.
 *
 * Ok and Skipped (another run is in flight) are success; a failure is retried
 * only when [pl.lk.toyotacompanion.core.sync.SyncError.retryable].
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: SyncRunner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val outcome = runner.fullSync()) {
        is SyncOutcome.Ok -> Result.success()
        SyncOutcome.Skipped -> Result.success()
        is SyncOutcome.Failed -> if (outcome.error.retryable) Result.retry() else Result.failure()
    }
}

/** Creates [SyncWorker] with the runner from the app container (no Hilt/Koin). */
class AppWorkerFactory(private val runner: () -> SyncRunner) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters, runner()) else null
}
