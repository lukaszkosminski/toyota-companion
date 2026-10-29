package pl.lk.toyotacompanion.core.toyota

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.toyota.dto.TelemetryDto
import pl.lk.toyotacompanion.core.toyota.dto.TripDto
import pl.lk.toyotacompanion.core.toyota.dto.UnitValueDto
import pl.lk.toyotacompanion.core.toyota.dto.VehicleDto

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
        val km = toKm(dto.odometer) ?: return null
        return FuelReading(
            ts = ts,
            levelPct = level,
            odometerKm = km,
            batteryLevelPct = dto.batteryLevel,
            distanceToEmptyKm = toKm(dto.distanceToEmpty),
        )
    }

    /**
     * Archive record of a trip: the domain [Trip] plus the fields the cost logic
     * never touches but the archive must keep (PRD F5, F11-F13). Returns null
     * exactly when [toDomainTrip] returns null — a payload unusable for the
     * cost model is unusable for the archive too.
     */
    fun toTripRecord(dto: TripDto, vin: String, syncedAt: Instant): TripRecord? {
        val trip = toDomainTrip(dto) ?: return null
        val summary = dto.summary ?: return null
        return TripRecord(
            trip = trip,
            vin = vin,
            startLat = summary.startLat,
            startLon = summary.startLon,
            endLat = summary.endLat,
            endLon = summary.endLon,
            durationIdleS = summary.durationIdle,
            averageSpeed = summary.averageSpeed,
            maxSpeed = summary.maxSpeed,
            nightTrip = summary.nightTrip,
            scoreGlobal = dto.scores?.global,
            scoreAcceleration = dto.scores?.acceleration,
            scoreBraking = dto.scores?.braking,
            scoreAdvice = dto.scores?.advice,
            scoreConstantSpeed = dto.scores?.constantSpeed,
            hdcEvDistanceM = dto.hdc?.evDistance,
            hdcEvTimeS = dto.hdc?.evTime,
            hdcEcoDistM = dto.hdc?.ecoDist,
            hdcEcoTimeS = dto.hdc?.ecoTime,
            hdcPowerDistM = dto.hdc?.powerDist,
            hdcPowerTimeS = dto.hdc?.powerTime,
            hdcChargeDistM = dto.hdc?.chargeDist,
            hdcChargeTimeS = dto.hdc?.chargeTime,
            syncedAt = syncedAt,
        )
    }

    /**
     * Archived vehicle. Identity is the VIN (the payload has no literal guid
     * field, see the note on `VehicleDto`); a payload without VIN is useless.
     */
    fun toVehicleRecord(dto: VehicleDto, seenAt: Instant): VehicleRecord? {
        val vin = dto.vin ?: return null
        return VehicleRecord(
            vin = vin,
            brand = dto.brand,
            modelName = dto.modelName,
            fuelType = dto.fuelType,
            evVehicle = dto.evVehicle,
            firstSeen = seenAt,
            lastSeen = seenAt,
        )
    }

    private fun toKm(value: UnitValueDto?): Double? {
        val number = value?.value ?: return null
        return when (value.unit?.lowercase()) {
            null, "km" -> number
            "mi", "miles" -> number * ToyotaConst.MILES_TO_KM_FACTOR
            else -> null
        }
    }

    /**
     * Toyota normally sends ISO-8601 with `Z`; accept a missing zone as UTC
     * (PRD: all dates are UTC) instead of silently dropping the trip.
     */
    private fun parseInstant(value: String): Instant? =
        try {
            Instant.parse(value)
        } catch (e: Exception) {
            try {
                java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant(java.time.ZoneOffset.UTC)
            } catch (e: Exception) {
                null
            }
        }
}
