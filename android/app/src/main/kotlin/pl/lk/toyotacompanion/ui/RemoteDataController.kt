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
 * One read of the read-only Toyota data (docs/SPEC.md §42-§44). [data] null with [failed] false means
 * Toyota answered without a payload (the screen says "no data"); [failed] true means the call itself broke;
 * [unavailable] true means Toyota refused the endpoint (403/404: retired or not supported for this car).
 */
data class RemoteDataUiState<T>(
    val loading: Boolean = true,
    val data: T? = null,
    val failed: Boolean = false,
    val unavailable: Boolean = false,
)

/**
 * The controller behind "Stan pojazdu", "Lokalizacja" and "Stan techniczny": read once when the screen
 * opens ([ensureLoaded]), never polled. No database, no cache on disk — the location in particular stays
 * in memory only (docs/SECURITY.md). [reset] on logout invalidates late answers.
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

    fun ensureLoaded() {
        if (!loaded) load()
    }

    fun load() {
        loaded = true
        val startedIn = generation
        ui.value = RemoteDataUiState(loading = true)
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
            if (generation != startedIn) return@launch
            ui.value = RemoteDataUiState(loading = false, data = data, failed = failed, unavailable = unavailable)
        }
    }

    fun reset() {
        generation++
        loaded = false
        ui.value = RemoteDataUiState()
    }
}
