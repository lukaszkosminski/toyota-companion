package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.prices.FuelPriceException
import pl.lk.toyotacompanion.core.prices.FuelPriceGateway
import pl.lk.toyotacompanion.core.prices.PriceAge
import pl.lk.toyotacompanion.core.prices.PriceErrorKind
import pl.lk.toyotacompanion.core.prices.StationPrice

private class FakePrices : FuelPriceGateway {
    var calls = 0
    var failure: FuelPriceException? = null
    var gate: CompletableDeferred<Unit>? = null
    var asked: Set<String> = emptySet()
    var answer = listOf(
        StationPrice("33831", BigDecimal("6.30"), PriceAge.TODAY),
        StationPrice("97185", BigDecimal("6.29"), PriceAge.YESTERDAY),
    )

    override suspend fun prices(stationIds: Set<String>): List<StationPrice> {
        calls++
        asked = stationIds
        gate?.await()
        failure?.let { throw it }
        return answer
    }
}

/** docs/SPEC.md §25: station prices are fetched on a tap, reused briefly, and never saved by themselves. */
class StationPriceControllerTest {
    private var now = Instant.parse("2026-11-01T12:00:00Z")

    private fun controller(gateway: FakePrices, io: kotlinx.coroutines.CoroutineDispatcher) =
        StationPriceController(gateway, CoroutineScope(SupervisorJob() + io), io) { now }

    @Test
    fun `a tap fetches the two stations once and keeps the prices`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakePrices()
        val c = controller(gateway, io)
        c.ensureFresh()
        advanceUntilIdle()
        assertEquals(setOf("33831", "97185"), gateway.asked)
        assertEquals(BigDecimal("6.30"), c.state.value.prices.getValue("33831").price95)
        assertFalse(c.state.value.fetching)
        assertNull(c.state.value.error)
    }

    @Test
    fun `fresh prices are reused and old ones are fetched again`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakePrices()
        val c = controller(gateway, io)
        c.ensureFresh()
        advanceUntilIdle()
        now = now.plus(Duration.ofMinutes(9))
        c.ensureFresh()
        advanceUntilIdle()
        assertEquals(1, gateway.calls)
        now = now.plus(Duration.ofMinutes(2))
        c.ensureFresh()
        advanceUntilIdle()
        assertEquals(2, gateway.calls)
    }

    @Test
    fun `a second tap while a request runs does not start another`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakePrices().apply { gate = CompletableDeferred() }
        val c = controller(gateway, io)
        c.ensureFresh()
        advanceUntilIdle()
        assertTrue(c.state.value.fetching)
        c.ensureFresh()
        advanceUntilIdle()
        assertEquals(1, gateway.calls)
        gateway.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(c.state.value.fetching)
    }

    @Test
    fun `a failure is a fixed result, is retried on the next tap and keeps nothing stale`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakePrices().apply { failure = FuelPriceException(PriceErrorKind.NETWORK, "secret host") }
        val c = controller(gateway, io)
        c.ensureFresh()
        advanceUntilIdle()
        assertEquals(PriceFetchResult.NETWORK, c.state.value.error)
        assertFalse(Texts.priceFetchError(PriceFetchResult.NETWORK).contains("secret"))
        gateway.failure = null
        c.ensureFresh()
        advanceUntilIdle()
        assertEquals(2, gateway.calls)
        assertNull(c.state.value.error)
    }

    @Test
    fun `reset drops the prices and a late answer`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakePrices().apply { gate = CompletableDeferred() }
        val c = controller(gateway, io)
        c.ensureFresh()
        advanceUntilIdle()
        c.reset()
        gateway.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyMap(), c.state.value.prices)
        assertFalse(c.state.value.fetching)
    }

    @Test
    fun `the note says how old the price is and warns about an old one`() {
        val auchan = PRICE_STATIONS.first()
        val state = StationPriceState(prices = emptyMap(), fetchedAt = now)
        val today = StationPrice(auchan.id, BigDecimal("6.30"), PriceAge.TODAY)
        val old = StationPrice(auchan.id, BigDecimal("6.29"), PriceAge.OLDER)
        assertEquals(
            "Auchan Szczęśliwa: 6,30 zł/l, wpisana dziś (ceny wpisują użytkownicy portalu i stacje).",
            StationPriceFormatting.note(auchan, state, today),
        )
        assertTrue(StationPriceFormatting.note(auchan, state, old).contains("może być nieaktualna"))
        assertTrue(StationPriceFormatting.note(auchan, state, null).contains("Wpisz cenę ręcznie"))
        assertTrue(StationPriceFormatting.note(auchan, state, StationPrice(auchan.id, null, null)).contains("nikt nie wpisał"))
        assertEquals(Texts.PRICE_FETCHING, StationPriceFormatting.note(auchan, state.copy(fetching = true), null))
        assertTrue(StationPriceFormatting.note(auchan, state.copy(error = PriceFetchResult.DATA), null).contains("Wpisz cenę ręcznie"))
    }

    @Test
    fun `the owner's two stations are Auchan Szczesliwa and WATIS`() {
        assertEquals(listOf("Auchan Szczęśliwa", "WATIS"), PRICE_STATIONS.map { it.name })
        assertEquals(listOf("33831", "97185"), PRICE_STATIONS.map { it.id })
    }
}
