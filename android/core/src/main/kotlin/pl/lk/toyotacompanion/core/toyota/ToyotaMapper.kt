package pl.lk.toyotacompanion.core.toyota

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.toyota.dto.TelemetryDto
import pl.lk.toyotacompanion.core.toyota.dto.TripDto

/**
 * Maps wire DTOs onto the pure domain types.
 *
 * Units (verified in pytoyoda v6.0.0):
 * - `summary.length` is meters: distance = `length / 1000.0` km
 *   (pytoyoda `models/trips.py:121-132`, `distance` computed property).
 * - `hdc.evDistance` is meters: `ev_distance = evDistance / 1000.0` km
 *   (pytoyoda `models/trips.py:151-167`).
 * - `summary.fuelConsumption` is milliliters: liters = `ml / 1000`
 *   (pytoyoda `models/endpoints/trips.py:35-37` "Milliliters" comment and
 *   `const.py:84` ML_TO_L_FACTOR = 1000.0).
 * - Cross-check: average fuel consumption `fuel/length*100` yields l/100km
 *   only for ml over meters (pytoyoda `models/trips.py:202-208`,
 *   integration assertion 1.485 l/100km for 226 ml / 15215 m:
 *   `tests/integration_tests/test_authentication.py:170`).
 */
object ToyotaMapper {

    private const val KM_SCALE = 3

    /** Liters scale 3, HALF_UP — consistent with domain rounding. */
    private const val LITERS_SCALE = 3

    /**
     * Domain Trip from a list/detail trip DTO.
     * Returns null when mandatory fields (id, timestamps, length, fuel) are
     * missing — Toyota payloads are patchy (pytoyoda trips.py:16-20 comment).
     */
    fun toDomainTrip(dto: TripDto): Trip? {
        val summary = dto.summary ?: return null
        val id = dto.id ?: return null
        val start = summary.startTs ?: return null
        val end = summary.endTs ?: return null
        val lengthM = summary.length ?: return null
        val fuelMl = summary.fuelConsumption ?: return null
        return Trip(
            id = id,
            start = parseInstant(start) ?: return null,
            end = parseInstant(end) ?: return null,
            distanceKm = lengthM / 1000.0,
            fuelL = BigDecimal(fuelMl.toString())
                .divide(BigDecimal(ToyotaConst.ML_TO_L_FACTOR.toLong()), LITERS_SCALE, RoundingMode.HALF_UP),
            evDistanceKm = dto.hdc?.evDistance?.let { it / 1000.0 },
        )
    }

    /**
     * Domain FuelReading from /v3/telemetry payload.
     * Odometer accepts unit "km" as-is; "miles"/"mi" converted with
     * MILES_TO_KM_FACTOR (pytoyoda const.py:86). Returns null when fuel level,
     * timestamp or odometer is absent.
     */
    fun toFuelReading(dto: TelemetryDto): FuelReading? {
        val level = dto.fuelLevel ?: return null
        val ts = parseInstant(dto.timestamp ?: return null) ?: return null
        val odometer = dto.odometer ?: return null
        val value = odometer.value ?: return null
        val km = when (odometer.unit?.lowercase()) {
            null, "km" -> value
            "mi", "miles" -> value * ToyotaConst.MILES_TO_KM_FACTOR
            else -> return null
        }
        return FuelReading(ts = ts, levelPct = level, odometerKm = km)
    }

    private fun parseInstant(value: String): Instant? =
        try {
            Instant.parse(value)
        } catch (e: Exception) {
            null
        }
}
