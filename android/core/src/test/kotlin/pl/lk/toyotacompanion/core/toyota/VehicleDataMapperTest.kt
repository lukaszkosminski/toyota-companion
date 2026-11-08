package pl.lk.toyotacompanion.core.toyota

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import pl.lk.toyotacompanion.core.domain.DoorPosition
import pl.lk.toyotacompanion.core.domain.WindowPosition
import pl.lk.toyotacompanion.core.toyota.dto.LocationResponse
import pl.lk.toyotacompanion.core.toyota.dto.VehicleHealthResponse
import pl.lk.toyotacompanion.core.toyota.dto.VehicleStatusResponse

/** docs/SPEC.md §42-§44: the read-only Toyota payloads map to the domain without guessing missing parts. */
class VehicleDataMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `vehicle status maps every door and window`() {
        val payload = """
            {"payload":{
              "vin":"VIN1",
              "lastUpdateTimestamp":"2026-10-29T10:00:00Z",
              "overallStatus":"ok",
              "overallWarningCounts":2,
              "leftHandDrive":true,
              "doors":{
                "driver":{"lockStatus":{"status":"locked","lastUpdateTimestamp":"2026-10-29T09:55:00Z"},
                          "openStatus":{"status":"close","lastUpdateTimestamp":"2026-10-29T09:56:00Z"}},
                "passenger":{"lockStatus":{"status":"locked"},"openStatus":{"status":"close"}},
                "rearLeft":{"openStatus":{"status":"open"}},
                "rearRight":{"openStatus":{"status":"close"}},
                "rearBack":{"openStatus":{"status":"close"}},
                "hood":{"openStatus":{"status":"close"}}
              },
              "windows":{
                "driver":{"status":"close","lastUpdateTimestamp":"2026-10-29T09:57:00Z"},
                "passenger":{"status":"open"},
                "rearLeft":{"status":"close"},
                "rearRight":{"status":"close"}
              },
              "someNewField":123
            }}
        """.trimIndent()
        val status = VehicleDataMapper.toVehicleStatus(json.decodeFromString<VehicleStatusResponse>(payload).payload)

        assertEquals(Instant.parse("2026-10-29T10:00:00Z"), status?.lastUpdate)
        assertEquals(2, status?.warnings)
        assertEquals(6, status?.doors?.size)
        val driver = status?.doors?.get(DoorPosition.DRIVER)
        assertEquals(false, driver?.isOpen)
        assertEquals(true, driver?.isLocked)
        assertEquals(Instant.parse("2026-10-29T09:56:00Z"), driver?.observedAt)
        // A door that reports open only keeps the lock state unknown, never a made-up "unlocked".
        val rearLeft = status?.doors?.get(DoorPosition.REAR_LEFT)
        assertEquals(true, rearLeft?.isOpen)
        assertNull(rearLeft?.isLocked)
        assertEquals(4, status?.windows?.size)
        assertEquals(false, status?.windows?.get(WindowPosition.DRIVER)?.isOpen)
        assertEquals(true, status?.windows?.get(WindowPosition.PASSENGER)?.isOpen)
        assertEquals(Instant.parse("2026-10-29T09:57:00Z"), status?.windows?.get(WindowPosition.DRIVER)?.observedAt)
    }

    @Test
    fun `unknown states and a missing payload stay unknown`() {
        val payload = """
            {"payload":{"doors":{"driver":{"lockStatus":{"status":"weird"},"openStatus":{"status":"ajar"}}}}}
        """.trimIndent()
        val status = VehicleDataMapper.toVehicleStatus(json.decodeFromString<VehicleStatusResponse>(payload).payload)

        assertNull(status?.lastUpdate)
        assertNull(status?.warnings)
        val driver = status?.doors?.get(DoorPosition.DRIVER)
        assertNull(driver?.isOpen)
        assertNull(driver?.isLocked)
        assertTrue(status?.windows?.isEmpty() == true)

        assertNull(VehicleDataMapper.toVehicleStatus(null))
    }

    @Test
    fun `location maps the last known position and its times`() {
        val payload = """
            {"payload":{
              "lastTimestamp":"2026-10-29T18:00:00Z",
              "vehicleLocation":{
                "displayName":"Gdańsk, ul. Przykładowa",
                "latitude":54.372,
                "longitude":18.638,
                "locationAcquisitionDatetime":"2026-10-29T17:59:00Z"
              },
              "vin":"VIN1"
            }}
        """.trimIndent()
        val location = VehicleDataMapper.toVehicleLocation(json.decodeFromString<LocationResponse>(payload).payload)

        assertEquals(54.372, location?.latitude)
        assertEquals(18.638, location?.longitude)
        assertEquals("Gdańsk, ul. Przykładowa", location?.displayName)
        assertEquals(Instant.parse("2026-10-29T17:59:00Z"), location?.acquiredAt)
        assertEquals(Instant.parse("2026-10-29T18:00:00Z"), location?.lastUpdate)

        val bare = VehicleDataMapper.toVehicleLocation(json.decodeFromString<LocationResponse>("""{"payload":{"lastTimestamp":"x"}}""").payload)
        assertNull(bare?.latitude)
        assertNull(bare?.lastUpdate)
        assertNull(VehicleDataMapper.toVehicleLocation(null))
    }

    @Test
    fun `health counts the warnings and says whether oil information came`() {
        val payload = """
            {"payload":{
              "quantityOfEngOilIcon":[{"icon":"ok"}],
              "vin":"VIN1",
              "warning":[{"code":"W1"},{"code":"W2"}],
              "wnglastUpdTime":"2026-10-29T08:00:00Z"
            }}
        """.trimIndent()
        val health = VehicleDataMapper.toVehicleHealth(json.decodeFromString<VehicleHealthResponse>(payload).payload)

        assertEquals(2, health?.warnings)
        assertTrue(health?.hasOilInfo == true)
        assertEquals(Instant.parse("2026-10-29T08:00:00Z"), health?.lastUpdate)

        val empty = VehicleDataMapper.toVehicleHealth(
            json.decodeFromString<VehicleHealthResponse>("""{"payload":{"warning":[]}}""").payload,
        )
        assertEquals(0, empty?.warnings)
        assertFalse(empty?.hasOilInfo == true)
        assertNull(VehicleDataMapper.toVehicleHealth(null))
    }
}
