package pl.lk.toyotacompanion.ui

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.prices.FuelPriceException
import pl.lk.toyotacompanion.core.prices.FuelPriceGateway
import pl.lk.toyotacompanion.core.prices.PriceErrorKind
import pl.lk.toyotacompanion.core.prices.StationPrice

/** A station the owner can take the price from: the id on trojmiasto.pl and the name shown (docs/SPEC.md §25). */
data class PriceStation(val id: String, val name: String)

/** The two stations the owner fills up at; the price can also be typed by hand. */
val PRICE_STATIONS: List<PriceStation> = listOf(
    PriceStation("33831", "Auchan Szczęśliwa"),
    PriceStation("97185", "WATIS"),
)

/** Prices fetched less than this long ago are reused: one page per few minutes is plenty for a human. */
val PRICE_FRESH_FOR: Duration = Duration.ofMinutes(10)

enum class PriceFetchResult { NETWORK, API, DATA }

/**
 * @param prices the last fetched 95 price per station id
 * @param fetchedAt when they were fetched, `null` before the first fetch
 * @param fetching a request is running
 * @param error how the last fetch failed; the text comes from [Texts], never from an exception
 */
data class StationPriceState(
    val prices: Map<String, StationPrice> = emptyMap(),
    val fetchedAt: Instant? = null,
    val fetching: Boolean = false,
    val error: PriceFetchResult? = null,
)

/**
 * Station prices for the price fields (docs/SPEC.md §25): fetched only when the owner taps a station, never in the
 * background, and reused for [PRICE_FRESH_FOR]. Nothing is saved: the price lands in the field, and the owner still
 * presses "Zapisz". [reset] on logout.
 */
class StationPriceController(
    private val gateway: FuelPriceGateway,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val ui = MutableStateFlow(StationPriceState())
    val state: StateFlow<StationPriceState> = ui.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    /** Fetches the prices unless they are fresh or a request already runs. */
    fun ensureFresh() {
        val current = ui.value
        if (job?.isActive == true) return
        val at = current.fetchedAt
        if (current.error == null && at != null && Duration.between(at, clock()) < PRICE_FRESH_FOR) return
        val startedIn = generation
        ui.value = current.copy(fetching = true, error = null)
        job = scope.launch {
            val result: Result<List<StationPrice>> = try {
                Result.success(withContext(io) { gateway.prices(PRICE_STATIONS.mapTo(HashSet()) { it.id }) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: FuelPriceException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(FuelPriceException(PriceErrorKind.DATA, "Unexpected price failure"))
            }
            if (generation != startedIn) return@launch
            ui.value = if (result.isSuccess) {
                StationPriceState(prices = result.getOrThrow().associateBy { it.stationId }, fetchedAt = clock())
            } else {
                val kind = (result.exceptionOrNull() as FuelPriceException).kind
                ui.value.copy(
                    fetching = false,
                    error = when (kind) {
                        PriceErrorKind.NETWORK -> PriceFetchResult.NETWORK
                        PriceErrorKind.API -> PriceFetchResult.API
                        PriceErrorKind.DATA -> PriceFetchResult.DATA
                    },
                )
            }
        }
    }

    fun reset() {
        generation++
        job?.cancel()
        job = null
        ui.value = StationPriceState()
    }
}
