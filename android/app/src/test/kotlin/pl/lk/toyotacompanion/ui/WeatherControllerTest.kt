package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pl.lk.toyotacompanion.core.data.InMemoryWeatherRepository
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.weather.WeatherDay
import pl.lk.toyotacompanion.core.weather.WeatherErrorKind
import pl.lk.toyotacompanion.core.weather.WeatherFetchException
import pl.lk.toyotacompanion.core.weather.WeatherGateway

private val NOW: Instant = Instant.parse("2026-11-01T10:00:00Z")
private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private fun ride(id: String, date: String, lat: Double = 54.372, lon: Double = 18.638): StoredTrip {
    val begin = Instant.parse("${date}T08:00:00Z")
    return StoredTrip(
        TripRecord(
            trip = Trip(id, begin, begin.plusSeconds(1_200), 10.0, BigDecimal("0.500"), null),
            vin = "VINAAA111",
            startLat = lat,
            startLon = lon,
        ),
        null,
    )
}

/** Gateway that answers every day of the range with the same weather and remembers what it was asked. */
private class FakeGateway : WeatherGateway {
    val asked = ArrayList<List<Any>>()
    var failure: WeatherFetchException? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun daily(latitude: Double, longitude: Double, start: LocalDate, end: LocalDate): List<WeatherDay> {
        asked += listOf(latitude, longitude, start, end)
        gate?.await()
        failure?.let { throw it }
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
            .map { WeatherDay(it, 4.0, 1.0, 7.0, 0.0, 0.0, 10.0) }.toList()
    }
}

/** docs/SPEC.md §23: the weather screen reads the cache, and fetches only when asked. */
class WeatherControllerTest {
    private fun controller(
        trips: List<StoredTrip>,
        cache: InMemoryWeatherRepository,
        gateway: FakeGateway,
        io: CoroutineDispatcher,
    ) = WeatherController(
        readTrips = { trips },
        cache = cache,
        gateway = gateway,
        zone = { WARSAW },
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
        clock = { NOW },
    )

    @Test
    fun `loading never asks the service`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        val c = controller(listOf(ride("a", "2026-10-01")), InMemoryWeatherRepository(), gateway, io)
        c.load()
        advanceUntilIdle()
        val view = assertNotNull(c.state.value.view)
        assertEquals(1, view.plannedPlaces)
        assertEquals(0, gateway.asked.size)
        assertEquals(1, view.temperature.tripsWithoutWeather)
    }

    @Test
    fun `a fetch asks the rounded place once, saves the days and fills the buckets`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        val cache = InMemoryWeatherRepository()
        val trips = (1..6).map { ride("t$it", "2026-10-0$it") }
        val c = controller(trips, cache, gateway, io)
        c.load()
        advanceUntilIdle()
        c.fetch()
        advanceUntilIdle()
        assertEquals(1, gateway.asked.size)
        assertEquals(listOf<Any>(54.37, 18.64, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-06")), gateway.asked.single())
        assertEquals(6, cache.count())
        val state = c.state.value
        assertEquals(WeatherFetchResult.DONE, state.lastFetch)
        assertFalse(state.fetching)
        val view = assertNotNull(state.view)
        assertEquals(0, view.temperature.tripsWithoutWeather)
        assertEquals(6, view.temperature.buckets.single().trips)
        assertEquals(0, view.plannedPlaces)
    }

    @Test
    fun `a second tap while a request runs does nothing`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val c = controller(listOf(ride("a", "2026-10-01")), InMemoryWeatherRepository(), gateway, io)
        c.load()
        advanceUntilIdle()
        c.fetch()
        advanceUntilIdle()
        assertTrue(c.state.value.fetching)
        c.fetch()
        advanceUntilIdle()
        assertEquals(1, gateway.asked.size)
        gateway.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(c.state.value.fetching)
    }

    @Test
    fun `a failure shows a fixed result and keeps what was cached`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway().apply { failure = WeatherFetchException(WeatherErrorKind.RATE_LIMIT, "HTTP 429 secret") }
        val cache = InMemoryWeatherRepository()
        val c = controller(listOf(ride("a", "2026-10-01")), cache, gateway, io)
        c.load()
        advanceUntilIdle()
        c.fetch()
        advanceUntilIdle()
        assertEquals(WeatherFetchResult.RATE_LIMIT, c.state.value.lastFetch)
        assertEquals(0, cache.count())
        assertNotNull(c.state.value.view)
        assertFalse(Texts.weatherFetchResult(WeatherFetchResult.RATE_LIMIT).contains("secret"))
    }

    @Test
    fun `nothing to fetch is said so and no request is made`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        val c = controller(emptyList(), InMemoryWeatherRepository(), gateway, io)
        c.load()
        advanceUntilIdle()
        c.fetch()
        advanceUntilIdle()
        assertEquals(WeatherFetchResult.NOTHING_TO_FETCH, c.state.value.lastFetch)
        assertEquals(0, gateway.asked.size)
    }

    @Test
    fun `more than ten places are fetched over several taps`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        val trips = (1..12).map { ride("p$it", "2026-10-01", lat = 50.0 + it) }
        val c = controller(trips, InMemoryWeatherRepository(), gateway, io)
        c.load()
        advanceUntilIdle()
        c.fetch()
        advanceUntilIdle()
        assertEquals(10, gateway.asked.size)
        assertEquals(WeatherFetchResult.PARTIAL, c.state.value.lastFetch)
        assertEquals(2, c.state.value.view?.plannedPlaces)
        c.fetch()
        advanceUntilIdle()
        assertEquals(12, gateway.asked.size)
        assertEquals(WeatherFetchResult.DONE, c.state.value.lastFetch)
    }

    @Test
    fun `reset forgets the state and a late result is dropped`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val c = controller(listOf(ride("a", "2026-10-01")), InMemoryWeatherRepository(), gateway, io)
        c.load()
        advanceUntilIdle()
        c.fetch()
        advanceUntilIdle()
        c.reset()
        gateway.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(c.state.value.loading)
        assertNull(c.state.value.view)
        assertNull(c.state.value.lastFetch)
    }
}
