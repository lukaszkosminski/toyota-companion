package pl.lk.toyotacompanion.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.math.BigDecimal
import java.time.Instant
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncState
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.data.VehicleRecord
import pl.lk.toyotacompanion.core.data.WeatherRow
import pl.lk.toyotacompanion.core.weather.WeatherDay
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

/** Room rows 1:1 with the types in `core.data` / `core.domain`; mapping lives next to each entity. */

@Entity(tableName = "trips", indices = [Index(value = ["vin", "start_ts"])])
data class TripEntity(
    @PrimaryKey val id: String,
    val vin: String,
    @ColumnInfo(name = "start_ts") val start: Instant,
    @ColumnInfo(name = "end_ts") val end: Instant,
    @ColumnInfo(name = "distance_km") val distanceKm: Double,
    @ColumnInfo(name = "fuel_l") val fuelL: BigDecimal,
    @ColumnInfo(name = "ev_distance_km") val evDistanceKm: Double?,
    @ColumnInfo(name = "start_lat") val startLat: Double?,
    @ColumnInfo(name = "start_lon") val startLon: Double?,
    @ColumnInfo(name = "end_lat") val endLat: Double?,
    @ColumnInfo(name = "end_lon") val endLon: Double?,
    @ColumnInfo(name = "duration_idle_s") val durationIdleS: Long?,
    @ColumnInfo(name = "average_speed") val averageSpeed: Double?,
    @ColumnInfo(name = "max_speed") val maxSpeed: Double?,
    @ColumnInfo(name = "night_trip") val nightTrip: Boolean?,
    @ColumnInfo(name = "score_global") val scoreGlobal: Int?,
    @ColumnInfo(name = "score_acceleration") val scoreAcceleration: Int?,
    @ColumnInfo(name = "score_braking") val scoreBraking: Int?,
    @ColumnInfo(name = "score_advice") val scoreAdvice: Int?,
    @ColumnInfo(name = "score_constant_speed") val scoreConstantSpeed: Int?,
    @ColumnInfo(name = "hdc_ev_distance_m") val hdcEvDistanceM: Long?,
    @ColumnInfo(name = "hdc_ev_time_s") val hdcEvTimeS: Long?,
    @ColumnInfo(name = "hdc_eco_dist_m") val hdcEcoDistM: Long?,
    @ColumnInfo(name = "hdc_eco_time_s") val hdcEcoTimeS: Long?,
    @ColumnInfo(name = "hdc_power_dist_m") val hdcPowerDistM: Long?,
    @ColumnInfo(name = "hdc_power_time_s") val hdcPowerTimeS: Long?,
    @ColumnInfo(name = "hdc_charge_dist_m") val hdcChargeDistM: Long?,
    @ColumnInfo(name = "hdc_charge_time_s") val hdcChargeTimeS: Long?,
    @ColumnInfo(name = "synced_at") val syncedAt: Instant?,
    // Since schema 6 (docs/SPEC.md §41): nullable, so a trip stored before has "not reported".
    @ColumnInfo(name = "overspeed_length_m") val overspeedLengthM: Long? = null,
    @ColumnInfo(name = "overspeed_duration_s") val overspeedDurationS: Long? = null,
    @ColumnInfo(name = "highway_length_m") val highwayLengthM: Long? = null,
    @ColumnInfo(name = "highway_duration_s") val highwayDurationS: Long? = null,
) {
    fun toRecord(): TripRecord = TripRecord(
        trip = Trip(id, start, end, distanceKm, fuelL, evDistanceKm),
        vin = vin,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
        durationIdleS = durationIdleS,
        averageSpeed = averageSpeed,
        maxSpeed = maxSpeed,
        nightTrip = nightTrip,
        scoreGlobal = scoreGlobal,
        scoreAcceleration = scoreAcceleration,
        scoreBraking = scoreBraking,
        scoreAdvice = scoreAdvice,
        scoreConstantSpeed = scoreConstantSpeed,
        hdcEvDistanceM = hdcEvDistanceM,
        hdcEvTimeS = hdcEvTimeS,
        hdcEcoDistM = hdcEcoDistM,
        hdcEcoTimeS = hdcEcoTimeS,
        hdcPowerDistM = hdcPowerDistM,
        hdcPowerTimeS = hdcPowerTimeS,
        hdcChargeDistM = hdcChargeDistM,
        hdcChargeTimeS = hdcChargeTimeS,
        syncedAt = syncedAt,
        overspeedLengthM = overspeedLengthM,
        overspeedDurationS = overspeedDurationS,
        highwayLengthM = highwayLengthM,
        highwayDurationS = highwayDurationS,
    )
}

fun TripRecord.toEntity(): TripEntity = TripEntity(
    id = trip.id,
    vin = vin,
    start = trip.start,
    end = trip.end,
    distanceKm = trip.distanceKm,
    fuelL = trip.fuelL,
    evDistanceKm = trip.evDistanceKm,
    startLat = startLat,
    startLon = startLon,
    endLat = endLat,
    endLon = endLon,
    durationIdleS = durationIdleS,
    averageSpeed = averageSpeed,
    maxSpeed = maxSpeed,
    nightTrip = nightTrip,
    scoreGlobal = scoreGlobal,
    scoreAcceleration = scoreAcceleration,
    scoreBraking = scoreBraking,
    scoreAdvice = scoreAdvice,
    scoreConstantSpeed = scoreConstantSpeed,
    hdcEvDistanceM = hdcEvDistanceM,
    hdcEvTimeS = hdcEvTimeS,
    hdcEcoDistM = hdcEcoDistM,
    hdcEcoTimeS = hdcEcoTimeS,
    hdcPowerDistM = hdcPowerDistM,
    hdcPowerTimeS = hdcPowerTimeS,
    hdcChargeDistM = hdcChargeDistM,
    hdcChargeTimeS = hdcChargeTimeS,
    syncedAt = syncedAt,
    overspeedLengthM = overspeedLengthM,
    overspeedDurationS = overspeedDurationS,
    highwayLengthM = highwayLengthM,
    highwayDurationS = highwayDurationS,
)

@Entity(tableName = "refuels", indices = [Index(value = ["vin", "ts"])])
data class RefuelEntity(
    @PrimaryKey val id: String,
    val vin: String,
    val ts: Instant,
    @ColumnInfo(name = "odometer_km") val odometerKm: Double,
    @ColumnInfo(name = "level_before") val levelBefore: Int,
    @ColumnInfo(name = "level_after") val levelAfter: Int,
    val liters: BigDecimal?,
    @ColumnInfo(name = "price_per_liter") val pricePerLiter: BigDecimal?,
    @ColumnInfo(name = "total_cost") val totalCost: BigDecimal?,
    val status: RefuelStatus,
    @ColumnInfo(name = "liters_source") val litersSource: LitersSource?,
    @ColumnInfo(name = "filled_to_full") val filledToFull: Boolean?,
    val station: String? = null,
) {
    fun toDomain(): Refuel = Refuel(
        id, ts, odometerKm, levelBefore, levelAfter, liters, pricePerLiter, totalCost, status, litersSource, filledToFull,
        station,
    )
}

fun Refuel.toEntity(vin: String): RefuelEntity = RefuelEntity(
    id, vin, ts, odometerKm, levelBefore, levelAfter, liters, pricePerLiter, totalCost, status, litersSource, filledToFull,
    station,
)

@Entity(tableName = "fuel_readings", primaryKeys = ["vin", "ts"])
data class FuelReadingEntity(
    val vin: String,
    val ts: Instant,
    @ColumnInfo(name = "level_pct") val levelPct: Int,
    @ColumnInfo(name = "odometer_km") val odometerKm: Double,
    @ColumnInfo(name = "battery_level_pct") val batteryLevelPct: Int?,
    @ColumnInfo(name = "distance_to_empty_km") val distanceToEmptyKm: Double?,
) {
    fun toDomain(): FuelReading = FuelReading(ts, levelPct, odometerKm, batteryLevelPct, distanceToEmptyKm)
}

fun FuelReading.toEntity(vin: String): FuelReadingEntity =
    FuelReadingEntity(vin, ts, levelPct, odometerKm, batteryLevelPct, distanceToEmptyKm)

@Entity(tableName = "trip_costs")
data class TripCostEntity(
    @PrimaryKey @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "cost_pln") val costPln: BigDecimal?,
    @ColumnInfo(name = "price_per_liter") val pricePerLiter: BigDecimal?,
    @ColumnInfo(name = "refuel_id") val refuelId: String?,
    val source: CostSource,
) {
    fun toDomain(): TripCost = TripCost(tripId, costPln, pricePerLiter, refuelId, source)
}

fun TripCost.toEntity(): TripCostEntity = TripCostEntity(tripId, costPln, pricePerLiter, refuelId, source)

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val vin: String,
    val brand: String?,
    @ColumnInfo(name = "model_name") val modelName: String?,
    @ColumnInfo(name = "fuel_type") val fuelType: String?,
    @ColumnInfo(name = "ev_vehicle") val evVehicle: Boolean?,
    @ColumnInfo(name = "first_seen") val firstSeen: Instant,
    @ColumnInfo(name = "last_seen") val lastSeen: Instant,
) {
    fun toRecord(): VehicleRecord = VehicleRecord(vin, brand, modelName, fuelType, evVehicle, firstSeen, lastSeen)
}

fun VehicleRecord.toEntity(): VehicleEntity =
    VehicleEntity(vin, brand, modelName, fuelType, evVehicle, firstSeen, lastSeen)

/** Single row: [id] is always [SYNC_STATE_ID]. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SYNC_STATE_ID,
    val vin: String?,
    @ColumnInfo(name = "watermark_epoch_day") val watermarkEpochDay: Long?,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Instant?,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Instant?,
    @ColumnInfo(name = "last_vehicles_success_at") val lastVehiclesSuccessAt: Instant?,
    @ColumnInfo(name = "last_telemetry_success_at") val lastTelemetrySuccessAt: Instant?,
    @ColumnInfo(name = "last_trips_success_at") val lastTripsSuccessAt: Instant?,
    @ColumnInfo(name = "last_error_at") val lastErrorAt: Instant?,
    @ColumnInfo(name = "last_error_kind") val lastErrorKind: SyncErrorKind?,
    @ColumnInfo(name = "last_error_message") val lastErrorMessage: String?,
    @ColumnInfo(name = "last_error_status") val lastErrorStatus: Int?,
) {
    fun toDomain(): SyncState = SyncState(
        vin = vin,
        watermarkEpochDay = watermarkEpochDay,
        lastAttemptAt = lastAttemptAt,
        lastSuccessAt = lastSuccessAt,
        lastVehiclesSuccessAt = lastVehiclesSuccessAt,
        lastTelemetrySuccessAt = lastTelemetrySuccessAt,
        lastTripsSuccessAt = lastTripsSuccessAt,
        lastErrorAt = lastErrorAt,
        lastErrorKind = lastErrorKind,
        lastErrorMessage = lastErrorMessage,
        lastErrorStatus = lastErrorStatus,
    )
}

const val SYNC_STATE_ID = 1

fun SyncState.toEntity(): SyncStateEntity = SyncStateEntity(
    vin = vin,
    watermarkEpochDay = watermarkEpochDay,
    lastAttemptAt = lastAttemptAt,
    lastSuccessAt = lastSuccessAt,
    lastVehiclesSuccessAt = lastVehiclesSuccessAt,
    lastTelemetrySuccessAt = lastTelemetrySuccessAt,
    lastTripsSuccessAt = lastTripsSuccessAt,
    lastErrorAt = lastErrorAt,
    lastErrorKind = lastErrorKind,
    lastErrorMessage = lastErrorMessage,
    lastErrorStatus = lastErrorStatus,
)

/** Cached daily weather of one rounded place (docs/SPEC.md §23); `date` is `YYYY-MM-DD`, values may be null. */
@Entity(tableName = "weather", primaryKeys = ["lat_r", "lon_r", "date"])
data class WeatherEntity(
    @ColumnInfo(name = "lat_r") val latitude: String,
    @ColumnInfo(name = "lon_r") val longitude: String,
    val date: String,
    @ColumnInfo(name = "temp_mean") val tempMeanC: Double?,
    @ColumnInfo(name = "temp_min") val tempMinC: Double?,
    @ColumnInfo(name = "temp_max") val tempMaxC: Double?,
    @ColumnInfo(name = "precip_mm") val precipitationMm: Double?,
    @ColumnInfo(name = "precip_h") val precipitationHours: Double?,
    @ColumnInfo(name = "wind_max") val windMaxKmh: Double?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Instant,
    @ColumnInfo(name = "weather_code") val weatherCode: Int? = null,
) {
    fun toRow(): WeatherRow = WeatherRow(
        latitude,
        longitude,
        WeatherDay(
            java.time.LocalDate.parse(date),
            tempMeanC,
            tempMinC,
            tempMaxC,
            precipitationMm,
            precipitationHours,
            windMaxKmh,
            weatherCode,
        ),
        fetchedAt,
    )
}

fun WeatherRow.toEntity(): WeatherEntity = WeatherEntity(
    latitude,
    longitude,
    day.date.toString(),
    day.tempMeanC,
    day.tempMinC,
    day.tempMaxC,
    day.precipitationMm,
    day.precipitationHours,
    day.windMaxKmh,
    fetchedAt,
    day.weatherCode,
)

/** The GPS route of one trip as the compact text of `encodeRoute` (docs/SPEC.md §27); empty text = Toyota has none. */
@Entity(tableName = "trip_routes")
data class TripRouteEntity(
    @PrimaryKey @ColumnInfo(name = "trip_id") val tripId: String,
    val points: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Instant,
)

/** An expense that is not fuel: service, inspection, tyres... [dateEpochDay] is the day it was paid, any day, also in the past. */
@Entity(tableName = "expenses", indices = [Index(value = ["date_epoch_day"])])
data class ExpenseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "date_epoch_day") val dateEpochDay: Long,
    val category: String,
    @ColumnInfo(name = "amount_pln") val amountPln: BigDecimal,
    val note: String?,
)
