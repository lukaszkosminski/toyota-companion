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

private val NOW: Instant = Instant.parse("2026-11-02T10:00:00Z")
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
        auto: Boolean = true,
    ) = WeatherController(
        readTrips = { trips },
        cache = cache,
        gateway = gateway,
        zone = { WARSAW },
        scope = CoroutineScope(SupervisorJob() + io),
        io = io,
        autoEnabled = { auto },
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
        assertEquals(1, view.plannedRequests)
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
        assertEquals(0, view.plannedRequests)
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
        assertEquals(2, c.state.value.view?.plannedRequests)
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

    @Test
    fun `reset in the middle of a fetch stops the requests and a new tap does not run beside it`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val trips = (1..3).map { ride("p$it", "2026-10-01", lat = 50.0 + it) }
        val c = controller(trips, InMemoryWeatherRepository(), gateway, io)
        c.load()
        advanceUntilIdle()
        c.fetch()
        advanceUntilIdle()
        assertEquals(1, gateway.asked.size)
        c.reset()
        gateway.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, gateway.asked.size, "no further request after a reset")
    }

    @Test
    fun `leaving the screen cancels the fetch`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val trips = (1..3).map { ride("p$it", "2026-10-01", lat = 50.0 + it) }
        val c = controller(trips, InMemoryWeatherRepository(), gateway, io, auto = false)
        c.load()
        advanceUntilIdle()
        c.fetch()
        advanceUntilIdle()
        c.cancelFetch()
        gateway.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, gateway.asked.size)
        assertFalse(c.state.value.fetching)
        c.fetch()
        advanceUntilIdle()
        assertTrue(gateway.asked.size >= 2, "a new tap starts again")
    }

    @Test
    fun `an evening trip is fetchable the same evening in the local zone`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        // 23:30 UTC on 31.10 is already 1.11 in Warsaw; "today" is 1.11, so 31.10 (local) is yesterday.
        val late = StoredTrip(
            TripRecord(Trip("late", Instant.parse("2026-10-31T21:30:00Z"), Instant.parse("2026-10-31T21:50:00Z"), 5.0, BigDecimal("0.3"), null), "VINAAA111", startLat = 54.372, startLon = 18.638),
            null,
        )
        val c = WeatherController(
            readTrips = { listOf(late) },
            cache = InMemoryWeatherRepository(),
            gateway = gateway,
            zone = { WARSAW },
            scope = CoroutineScope(SupervisorJob() + io),
            io = io,
            clock = { Instant.parse("2026-10-31T23:30:00Z") },
        )
        c.load()
        advanceUntilIdle()
        // Local date of the clock is 1.11, the trip started 31.10 local: one request for it.
        assertEquals(1, c.state.value.view?.plannedRequests)
    }

    @Test
    fun `a service answer with no days is not a success and the days are not asked for forever`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = object : WeatherGateway {
            var calls = 0
            override suspend fun daily(latitude: Double, longitude: Double, start: LocalDate, end: LocalDate): List<WeatherDay> {
                calls++
                return emptyList()
            }
        }
        val cache = InMemoryWeatherRepository()
        val c = WeatherController({ listOf(ride("a", "2026-09-01")) }, cache, gateway, { WARSAW }, CoroutineScope(SupervisorJob() + io), io) { NOW }
        c.load()
        advanceUntilIdle()
        c.fetch()
        advanceUntilIdle()
        assertEquals(WeatherFetchResult.DATA, c.state.value.lastFetch)
        // The empty answer is cached as a blank day, so the old day is not planned again.
        assertEquals(1, cache.count())
        assertEquals(0, c.state.value.view?.plannedRequests)
    }

    @Test
    fun `a read that throws is shown as a failed read without its text`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val c = WeatherController({ throw IllegalStateException("secret 54.372") }, InMemoryWeatherRepository(), FakeGateway(), { WARSAW }, CoroutineScope(SupervisorJob() + io), io) { NOW }
        c.load()
        advanceUntilIdle()
        assertTrue(c.state.value.loadFailed)
        assertFalse(Texts.WEATHER_READ_ERROR.contains("secret"))
    }

    @Test
    fun `ensureLoaded reads only once`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var reads = 0
        val c = WeatherController({ reads++; emptyList() }, InMemoryWeatherRepository(), FakeGateway(), { WARSAW }, CoroutineScope(SupervisorJob() + io), io) { NOW }
        c.ensureLoaded()
        advanceUntilIdle()
        c.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, reads)
    }

    @Test
    fun `the automatic run fetches every missing place in one go, not ten per tap`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        val trips = (1..25).map { ride("p$it", "2026-10-01", lat = 40.0 + it) }
        val cache = InMemoryWeatherRepository()
        val c = controller(trips, cache, gateway, io)
        c.load()
        advanceUntilIdle()
        c.autoFetch()
        advanceUntilIdle()
        assertEquals(25, gateway.asked.size)
        assertEquals(WeatherFetchResult.DONE, c.state.value.lastFetch)
        assertEquals(0, c.state.value.view?.plannedRequests)
    }

    @Test
    fun `switched off, the automatic run does nothing`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        val c = controller(listOf(ride("a", "2026-10-01")), InMemoryWeatherRepository(), gateway, io, auto = false)
        c.load()
        advanceUntilIdle()
        c.autoFetch()
        advanceUntilIdle()
        assertEquals(0, gateway.asked.size)
    }

    @Test
    fun `an automatic run with nothing to fetch says nothing`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        val c = controller(emptyList(), InMemoryWeatherRepository(), gateway, io)
        c.load()
        advanceUntilIdle()
        c.autoFetch()
        advanceUntilIdle()
        assertNull(c.state.value.lastFetch)
        assertEquals(0, gateway.asked.size)
    }

    @Test
    fun `a run goes on after the screen is left while the switch is on`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway().apply { gate = CompletableDeferred() }
        val trips = (1..3).map { ride("p$it", "2026-10-01", lat = 50.0 + it) }
        val c = controller(trips, InMemoryWeatherRepository(), gateway, io)
        c.load()
        advanceUntilIdle()
        c.autoFetch()
        advanceUntilIdle()
        c.cancelFetch()
        gateway.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, gateway.asked.size)
    }

    @Test
    fun `the switch is saved, shown and turning it on starts a run`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val gateway = FakeGateway()
        var saved: Boolean? = null
        var on = false
        val c = WeatherController(
            readTrips = { listOf(ride("a", "2026-10-01")) },
            cache = InMemoryWeatherRepository(),
            gateway = gateway,
            zone = { WARSAW },
            scope = CoroutineScope(SupervisorJob() + io),
            io = io,
            autoEnabled = { on },
            saveAuto = { saved = it; on = it },
            clock = { NOW },
        )
        c.load()
        advanceUntilIdle()
        assertFalse(c.state.value.auto)
        c.setAuto(true)
        advanceUntilIdle()
        assertEquals(true, saved)
        assertTrue(c.state.value.auto)
        assertEquals(1, gateway.asked.size)
    }
}
