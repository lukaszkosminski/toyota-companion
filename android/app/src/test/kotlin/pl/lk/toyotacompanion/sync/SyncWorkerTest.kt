package pl.lk.toyotacompanion.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.sync.SyncError
import pl.lk.toyotacompanion.core.sync.SyncOutcome

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun run(outcome: SyncOutcome): Result {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(AppWorkerFactory { SyncRunner { outcome } })
            .build()
        return runBlocking { worker.doWork() }
    }

    private fun failed(kind: SyncErrorKind) = SyncOutcome.Failed(SyncError(kind, "x"), SyncState())

    @Test
    fun `Ok is success`() {
        assertEquals(Result.success(), run(SyncOutcome.Ok(1, 1, 0, 1, false, SyncState())))
    }

    @Test
    fun `Skipped is success`() {
        assertEquals(Result.success(), run(SyncOutcome.Skipped))
    }

    @Test
    fun `retryable failures retry`() {
        for (kind in listOf(SyncErrorKind.API, SyncErrorKind.NETWORK, SyncErrorKind.DATA, SyncErrorKind.STORAGE)) {
            assertEquals(Result.retry(), run(failed(kind)), kind.name)
        }
    }

    @Test
    fun `non retryable failures fail`() {
        for (kind in listOf(SyncErrorKind.NO_CREDENTIALS, SyncErrorKind.LOGIN, SyncErrorKind.NO_VEHICLE)) {
            assertEquals(Result.failure(), run(failed(kind)), kind.name)
        }
    }

    @Test
    fun `login schedules unique periodic work with a network constraint`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        SyncScheduler.onLogin(workManager)
        SyncScheduler.onLogin(workManager) // KEEP: second call must not add another

        val periodic = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME).get()
        assertEquals(1, periodic.size)
        assertEquals(NetworkType.CONNECTED, periodic.single().constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, periodic.single().state)

        val oneTime = workManager.getWorkInfosForUniqueWork(SyncScheduler.ONE_TIME_WORK_NAME).get()
        assertEquals(1, oneTime.size)
        assertEquals(NetworkType.CONNECTED, oneTime.single().constraints.requiredNetworkType)
    }

    @Test
    fun `periodic work runs every 30 minutes`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        SyncScheduler.schedulePeriodic(workManager)

        val info = workManager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME).get().single()
        assertEquals(30 * 60 * 1000L, info.periodicityInfo?.repeatIntervalMillis)
    }
}
