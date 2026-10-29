package pl.lk.toyotacompanion.core.sync

import java.time.Instant
import java.time.LocalDate
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.VehicleRecord

/**
 * Scripted [ToyotaGateway] for [SyncEngine] tests: no sockets, deterministic
 * windows, explicit failure injection and a suspension hook for the
 * single-flight test (docs/DOD.md: deterministic tests without network).
 */
class FakeToyotaGateway(
    var fleet: List<VehicleRecord> = emptyList(),
    var telemetry: FuelReading? = null,
    var tripsProvider: (vin: String, from: LocalDate, to: LocalDate) -> List<TripRecord> =
        { _, _, _ -> emptyList() },
    var hasTripsFor: (String, LocalDate, LocalDate) -> Boolean = { _, _, _ -> true },
) : ToyotaGateway {
    var vehiclesError: Exception? = null
    var telemetryError: Exception? = null
    var tripsError: Exception? = null

    /** Telemetry answers, one per call; the last one is reused when empty. */
    private val telemetryQueue = ArrayDeque<FuelReading?>()

    var vehiclesCalls = 0
    var telemetryCalls = 0
    var tripsCalls = 0
    val tripWindows = mutableListOf<Pair<LocalDate, LocalDate>>()

    /** Runs before every `vehicles()` call — used to hold the single flight open. */
    var beforeVehicles: suspend () -> Unit = {}

    fun enqueueTelemetry(reading: FuelReading?) {
        telemetryQueue.addLast(reading)
    }

    override suspend fun vehicles(): List<VehicleRecord> {
        vehiclesCalls++
        beforeVehicles()
        vehiclesError?.let { throw it }
        return fleet
    }

    override suspend fun telemetry(vin: String): FuelReading? {
        telemetryCalls++
        telemetryError?.let { throw it }
        return if (telemetryQueue.isNotEmpty()) telemetryQueue.removeFirst() else telemetry
    }

    override suspend fun trips(vin: String, from: LocalDate, to: LocalDate): List<TripRecord> {
        tripsCalls++
        tripWindows += from to to
        tripsError?.let { throw it }
        check(hasTripsFor(vin, from, to)) { "unexpected trip window $from..$to" }
        return tripsProvider(vin, from, to)
    }
}

/** Fixed clock so `Instant.now()` never leaks into a synchronization test. */
class FakeClock(start: Instant) {
    var now: Instant = start

    fun at(instant: Instant) {
        now = instant
    }

    fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }

    operator fun invoke(): Instant = now
}
