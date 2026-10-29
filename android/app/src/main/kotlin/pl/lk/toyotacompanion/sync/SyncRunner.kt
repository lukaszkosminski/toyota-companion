package pl.lk.toyotacompanion.sync

import pl.lk.toyotacompanion.core.sync.SyncOutcome

/** What the worker needs from the engine; lets tests supply a fake. */
fun interface SyncRunner {
    suspend fun fullSync(): SyncOutcome
}
