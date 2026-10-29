package pl.lk.toyotacompanion.core.data

/**
 * Single-row synchronization state: which vehicle we sync, the trip watermark
 * and the last attempt/success/error (docs/SPEC.md §8.3, §8.4).
 *
 * Error messages must already be sanitized by the caller — never VIN, token or
 * password (docs/SECURITY.md); [pl.lk.toyotacompanion.core.toyota.ToyotaSecrets.sanitize]
 * is the only allowed path.
 */
interface SyncStateRepository {
    /** The stored row, or an empty [SyncState] when the app never synchronized. */
    suspend fun get(): SyncState

    suspend fun save(state: SyncState)

    suspend fun deleteAll()
}
