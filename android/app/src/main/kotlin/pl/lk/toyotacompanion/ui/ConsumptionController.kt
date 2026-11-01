package pl.lk.toyotacompanion.ui

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip

/**
 * Average consumption and the monthly bars on the status screen (docs/SPEC.md §24): read once after the login and
 * again after every successful synchronization, from the local archive only. A failed read hides the card;
 * [reset] on logout.
 */
class ConsumptionController(
    private val read: suspend () -> List<StoredTrip>,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val ui = MutableStateFlow<ConsumptionView?>(null)
    val state: StateFlow<ConsumptionView?> = ui.asStateFlow()

    private var generation = 0
    private var loadStarted = false

    fun refresh() {
        loadStarted = true
        val startedIn = generation
        scope.launch {
            val view = try {
                withContext(io) { consumptionView(read(), zone()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (generation == startedIn) ui.value = view
        }
    }

    fun ensureLoaded() {
        if (!loadStarted) refresh()
    }

    fun reset() {
        generation++
        loadStarted = false
        ui.value = null
    }
}
