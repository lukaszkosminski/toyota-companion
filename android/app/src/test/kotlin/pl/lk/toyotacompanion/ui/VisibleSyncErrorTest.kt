package pl.lk.toyotacompanion.ui

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.SyncStatus

/**
 * Which error the status screen shows (docs/SPEC.md §9.3): a failure of the last in-process run has
 * priority, a background failure is shown from the stored state, and an old error never hides a fresh
 * success. Pure function, so no Compose and no Android here.
 */
class VisibleSyncErrorTest {

    private val success = Instant.parse("2026-11-13T10:00:00Z")
    private val errorAfter = Instant.parse("2026-11-13T11:00:00Z")
    private val errorBefore = Instant.parse("2026-11-12T09:00:00Z")

    private fun status(
        kind: SyncErrorKind? = null,
        errorAt: Instant? = null,
        successAt: Instant? = null,
    ) = SyncStatus(
        state = SyncState(lastErrorKind = kind, lastErrorAt = errorAt, lastSuccessAt = successAt),
        tripsStored = 0,
        readingsStored = 0,
        pendingRefuels = 0,
    )

    @Test
    fun `the failure of the last in-process run wins`() {
        val stored = status(kind = SyncErrorKind.NETWORK, errorAt = errorAfter, successAt = success)
        assertEquals(SyncErrorKind.API, visibleSyncError(stored, SyncErrorKind.API))
    }

    @Test
    fun `a stored error newer than the last success is shown`() {
        val stored = status(kind = SyncErrorKind.NETWORK, errorAt = errorAfter, successAt = success)
        assertEquals(SyncErrorKind.NETWORK, visibleSyncError(stored, null))
    }

    @Test
    fun `an old error after a fresh success is not shown`() {
        val stored = status(kind = SyncErrorKind.API, errorAt = errorBefore, successAt = success)
        assertNull(visibleSyncError(stored, null))
    }

    @Test
    fun `an error at the same moment as the success is not shown`() {
        val stored = status(kind = SyncErrorKind.STORAGE, errorAt = success, successAt = success)
        assertNull(visibleSyncError(stored, null))
    }

    @Test
    fun `without a success the stored error is shown`() {
        val stored = status(kind = SyncErrorKind.LOGIN, errorAt = errorBefore, successAt = null)
        assertEquals(SyncErrorKind.LOGIN, visibleSyncError(stored, null))
    }

    @Test
    fun `no error at all gives nothing`() {
        val stored = status(successAt = success)
        assertNull(visibleSyncError(stored, null))
        assertNull(visibleSyncError(null, null))
    }

    @Test
    fun `an in-process failure is shown even before the first status read`() {
        assertEquals(SyncErrorKind.STORAGE, visibleSyncError(null, SyncErrorKind.STORAGE))
    }
}
