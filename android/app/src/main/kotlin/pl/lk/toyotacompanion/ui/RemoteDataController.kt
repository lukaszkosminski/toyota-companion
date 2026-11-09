package pl.lk.toyotacompanion.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException

private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/**
 * One read of the read-only Toyota data (docs/SPEC.md §42-§47). [data] null with [failed] false means
 * Toyota answered without a payload (the screen says "no data"); [failed] true means the call itself broke;
 * [unavailable] true means Toyota refused the endpoint (403/404: retired or not supported for this car).
 * After a broken or refused retry [data] keeps the last good answer, so a transient error does not clear the
 * screen (docs/SPEC.md §47); [reset] drops it.
 */
data class RemoteDataUiState<T>(
    val loading: Boolean = true,
    val data: T? = null,
    val failed: Boolean = false,
    val unavailable: Boolean = false,
)

/**
 * The controller behind "Stan pojazdu", "Lokalizacja", "Stan techniczny", the service history, the
 * notifications and the diagnostics. The screens call [load] on every opening, so a reopened screen is
 * fresh; [ensureLoaded] reads at most once and is for callers that must not refresh. No database, no cache
 * on disk — the location in particular stays in memory only (docs/SECURITY.md).
 *
 * Every [load] gets its own token: an answer from an older call is dropped when a newer one already runs,
 * so a slow first request cannot overwrite a fresher second one. [reset] invalidates every answer still in
 * flight and drops the kept data.
 */
class RemoteDataController<T>(
    private val read: suspend () -> T?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow(RemoteDataUiState<T>())
    val state: StateFlow<RemoteDataUiState<T>> = ui.asStateFlow()

    private var generation = 0
    private var loaded = false

    /** Reads once if nothing was read since the last [reset]; a caller that wants a refresh calls [load]. */
    fun ensureLoaded() {
        if (!loaded) load()
    }

    fun load() {
        loaded = true
        val token = ++generation
        val previous = ui.value
        // The last good answer stays on screen while a retry runs (docs/SPEC.md §47).
        ui.value = RemoteDataUiState(loading = true, data = previous.data)
        scope.launch {
            var failed = false
            var unavailable = false
            val data = try {
                withContext(io) { read() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ToyotaApiException) {
                // 403/404: Toyota retired the route or the car does not support it — not a retryable error.
                if (e.statusCode == HTTP_FORBIDDEN || e.statusCode == HTTP_NOT_FOUND) unavailable = true else failed = true
                null
            } catch (e: Exception) {
                // The text of the exception is not shown (docs/SECURITY.md).
                failed = true
                null
            }
            if (token != generation) return@launch
            // A broken read keeps what was last good; an answer without a payload is an honest "no data".
            val shown = if (failed || unavailable) previous.data else data
            ui.value = RemoteDataUiState(loading = false, data = shown, failed = failed, unavailable = unavailable)
        }
    }

    fun reset() {
        generation++
        loaded = false
        ui.value = RemoteDataUiState()
    }
}
