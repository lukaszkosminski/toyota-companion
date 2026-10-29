@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.toyota

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.toyota.dto.HdcDto
import pl.lk.toyotacompanion.core.toyota.dto.ScoresDto
import pl.lk.toyotacompanion.core.toyota.dto.TripDto
import pl.lk.toyotacompanion.core.toyota.dto.TripSummaryDto
import pl.lk.toyotacompanion.core.toyota.dto.VehicleDto

private val SYNCED = Instant.parse("2026-10-29T08:00:00Z")

private fun fullTripDto() = TripDto(
    id = "b1cd2f3e-0000-4000-8000-000000000001",
    category = 1,
    summary = TripSummaryDto(
        length = 15_215L,
        duration = 1_500L,
        durationIdle = 120L,
        averageSpeed = 36.5,
        maxSpeed = 71.0,
        fuelConsumption = 226.0,
        startTs = "2026-10-05T09:00:00Z",
        startLat = 54.355,
        startLon = 18.646,
        endTs = "2026-10-05T09:25:00Z",
        endLat = 54.373,
        endLon = 18.618,
        nightTrip = false,
    ),
    scores = ScoresDto(global = 82, acceleration = 78, braking = 83, advice = 16, constantSpeed = 27),
    hdc = HdcDto(
        evTime = 63_937L,
        evDistance = 760_463L,
        ecoTime = 45_285L,
        ecoDist = 646_532L,
        powerTime = 2_491L,
        powerDist = 42_975L,
        chargeTime = 23_690L,
        chargeDist = 178_567L,
    ),
)

/**
 * Archive mapping (PRD F5, F11-F13): the fields the cost model ignores must not
 * be lost on the way to the database. Pure DTO in, record out — no network.
 */
class ToyotaMapperArchiveTest {

    @Test
    fun `toTripRecord_keeps_coordinates_scores_and_hdc`() {
        val record = requireNotNull(ToyotaMapper.toTripRecord(fullTripDto(), VIN_FAKE, SYNCED))

        assertEquals(54.355, requireNotNull(record.startLat))
        assertEquals(18.646, requireNotNull(record.startLon))
        assertEquals(54.373, requireNotNull(record.endLat))
        assertEquals(18.618, requireNotNull(record.endLon))
        assertEquals(120L, requireNotNull(record.durationIdleS))
        assertEquals(36.5, requireNotNull(record.averageSpeed))
        assertEquals(71.0, requireNotNull(record.maxSpeed))
        assertEquals(false, record.nightTrip)
        assertEquals(82, record.scoreGlobal)
        assertEquals(78, record.scoreAcceleration)
        assertEquals(83, record.scoreBraking)
        assertEquals(16, record.scoreAdvice)
        assertEquals(27, record.scoreConstantSpeed)
        assertEquals(760_463L, record.hdcEvDistanceM)
        assertEquals(63_937L, record.hdcEvTimeS)
        assertEquals(646_532L, record.hdcEcoDistM)
        assertEquals(45_285L, record.hdcEcoTimeS)
        assertEquals(42_975L, record.hdcPowerDistM)
        assertEquals(2_491L, record.hdcPowerTimeS)
        assertEquals(178_567L, record.hdcChargeDistM)
        assertEquals(23_690L, record.hdcChargeTimeS)
        assertEquals(VIN_FAKE, record.vin)
        assertEquals(SYNCED, record.syncedAt)
    }

    @Test
    fun `toTripRecord_projection_equals_toDomainTrip`() {
        val dto = fullTripDto()

        val projected = requireNotNull(ToyotaMapper.toTripRecord(dto, VIN_FAKE, SYNCED)).toTrip()
        val domain = requireNotNull(ToyotaMapper.toDomainTrip(dto))

        assertEquals(domain, projected)
        assertEquals(BigDecimal("0.226"), projected.fuelL)
        assertEquals(15.215, projected.distanceKm)
        assertEquals(760.463, requireNotNull(projected.evDistanceKm))
    }

    @Test
    fun `toTripRecord_null_exactly_when_domain_trip_is_null`() {
        val withoutFuel = fullTripDto().copy(summary = fullTripDto().summary?.copy(fuelConsumption = null))

        assertNull(ToyotaMapper.toTripRecord(withoutFuel, VIN_FAKE, SYNCED))
        assertNull(ToyotaMapper.toDomainTrip(withoutFuel))
    }

    @Test
    fun `toTripRecord_tolerates_missing_scores_and_hdc`() {
        val sparse = TripDto(
            id = "trip-sparse",
            summary = TripSummaryDto(
                length = 1_000L,
                fuelConsumption = 50.0,
                startTs = "2026-10-05T09:00:00Z",
                endTs = "2026-10-05T09:05:00Z",
            ),
        )

        val record = requireNotNull(ToyotaMapper.toTripRecord(sparse, VIN_FAKE, SYNCED))

        assertNull(record.scoreGlobal)
        assertNull(record.hdcEvDistanceM)
        assertNull(record.startLat)
        assertEquals(Trip("trip-sparse", record.trip.start, record.trip.end, 1.0, BigDecimal("0.050"), null), record.toTrip())
    }

    @Test
    fun `toVehicleRecord_maps_description_and_seen_stamps`() {
        val record = requireNotNull(
            ToyotaMapper.toVehicleRecord(
                VehicleDto(
                    vin = VIN_FAKE,
                    brand = "T",
                    modelName = "Corolla Hybrid",
                    fuelType = "HYBRID",
                    evVehicle = false,
                    subscriberGuid = "guid-1",
                ),
                SYNCED,
            ),
        )

        assertEquals(VIN_FAKE, record.vin)
        assertEquals("T", record.brand)
        assertEquals("Corolla Hybrid", record.modelName)
        assertEquals("HYBRID", record.fuelType)
        assertEquals(false, record.evVehicle)
        assertEquals(SYNCED, record.firstSeen)
        assertEquals(SYNCED, record.lastSeen)
    }

    @Test
    fun `toVehicleRecord_null_without_vin`() {
        assertNull(ToyotaMapper.toVehicleRecord(VehicleDto(modelName = "no vin"), SYNCED))
    }
}

private const val VIN_FAKE = "VINTEST0000000001"
