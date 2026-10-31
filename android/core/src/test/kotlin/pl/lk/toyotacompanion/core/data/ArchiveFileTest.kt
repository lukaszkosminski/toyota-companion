@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.core.data

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip

/**
 * The backup file format (PRD F7, docs/SPEC.md §14): what is written out must read back
 * identical, and a file that is foreign, newer or damaged must be refused as an outcome
 * instead of an exception.
 */
private val EXPORTED_AT: Instant = Instant.parse("2026-10-05T06:00:00Z")

private val TS: Instant = Instant.parse("2026-10-01T08:00:00Z")

/** The smallest valid file: an empty archive with a header. */
private val MINIMAL: String =
    """
    {
      "format":"toyota-companion-archive",
      "version":1,
      "exportedAt":"2026-10-05T06:00:00Z",
      "vehicles":[],
      "trips":[],
      "refuels":[],
      "readings":[]
    }
    """.trimIndent()

/** An envelope with the given collections, so a test can point at one field. */
private fun envelopeWith(
    vehicles: String = "[]",
    trips: String = "[]",
    refuels: String = "[]",
    readings: String = "[]",
): String =
    """{"format":"$ARCHIVE_FORMAT","version":$ARCHIVE_VERSION,"exportedAt":"$EXPORTED_AT",""" +
        """"vehicles":$vehicles,"trips":$trips,"refuels":$refuels,"readings":$readings}"""

private fun decode(text: String): ArchiveDecode = decodeArchive(text.toByteArray(Charsets.UTF_8))

private fun vehicle(vin: String, model: String?): VehicleRecord =
    VehicleRecord(vin, "Toyota", model, "PETROL", false, TS, EXPORTED_AT)

private fun fullTrip(id: String, vin: String): TripRecord = TripRecord(
    trip = Trip(id, TS, TS.plusSeconds(1_800), 12.4, BigDecimal("0.900"), 3.2),
    vin = vin,
    startLat = 54.3647,
    startLon = 18.6458,
    endLat = 54.3800,
    endLon = 18.6200,
    durationIdleS = 45L,
    averageSpeed = 24.8,
    maxSpeed = 71.5,
    nightTrip = false,
    scoreGlobal = 87,
    scoreAcceleration = 90,
    scoreBraking = 75,
    scoreAdvice = 80,
    scoreConstantSpeed = 88,
    hdcEvDistanceM = 1_200L,
    hdcEvTimeS = 300L,
    hdcEcoDistM = 2_400L,
    hdcEcoTimeS = 620L,
    hdcPowerDistM = 300L,
    hdcPowerTimeS = 60L,
    hdcChargeDistM = 900L,
    hdcChargeTimeS = 210L,
    syncedAt = EXPORTED_AT,
)

private fun bareTrip(id: String, vin: String): TripRecord =
    TripRecord(Trip(id, TS, TS.plusSeconds(600), 2.0, BigDecimal("0.200"), null), vin)

private fun refuel(id: String, vin: String, status: RefuelStatus, litersSource: LitersSource?): ArchiveRefuel =
    ArchiveRefuel(
        vin = vin,
        refuel = Refuel(
            id = id,
            ts = TS.plusSeconds(3_600),
            odometerKm = 41_230.0,
            levelBefore = 12,
            levelAfter = 96,
            liters = if (status == RefuelStatus.PENDING) null else BigDecimal("41.230"),
            pricePerLiter = if (status == RefuelStatus.CONFIRMED) BigDecimal("6.190") else null,
            totalCost = if (status == RefuelStatus.CONFIRMED) BigDecimal("255.214") else null,
            status = status,
            litersSource = litersSource,
            filledToFull = if (status == RefuelStatus.DISMISSED) null else true,
        ),
    )

private fun reading(vin: String, withOptional: Boolean): ArchiveReading = ArchiveReading(
    vin = vin,
    reading = FuelReading(
        ts = TS.plusSeconds(7_200),
        levelPct = 62,
        odometerKm = 41_230.0,
        batteryLevelPct = if (withOptional) 84 else null,
        distanceToEmptyKm = if (withOptional) 410.0 else null,
    ),
)

private fun snapshot(): ArchiveSnapshot = ArchiveSnapshot(
    exportedAt = EXPORTED_AT,
    vehicles = listOf(vehicle("VIN1", "Corolla"), vehicle("VIN2", null)),
    trips = listOf(fullTrip("trip-1", "VIN1"), bareTrip("trip-2", "VIN2")),
    refuels = listOf(
        refuel("refuel-1", "VIN1", RefuelStatus.CONFIRMED, LitersSource.RECEIPT),
        refuel("refuel-2", "VIN1", RefuelStatus.PENDING, LitersSource.ESTIMATE),
        refuel("refuel-3", "VIN2", RefuelStatus.DISMISSED, null),
    ),
    readings = listOf(reading("VIN1", withOptional = true), reading("VIN2", withOptional = false)),
)

private val EMPTY_SNAPSHOT = ArchiveSnapshot(EXPORTED_AT, emptyList(), emptyList(), emptyList(), emptyList())

private const val ONE_REFUEL =
    """{"vin":"VIN1","id":"refuel-1","ts":"2026-10-01T09:00:00Z","odometerKm":41230.0,"levelBefore":12,"levelAfter":96,"status":"PENDING"}"""

class ArchiveFileTest {
    @Test
    fun `a full snapshot reads back exactly as it was written`() {
        val original = snapshot()

        val decoded = assertIs<ArchiveDecode.Ok>(decodeArchive(encodeArchive(original)))

        assertEquals(original, decoded.snapshot)
    }

    @Test
    fun `decimal scale survives the file`() {
        val restored = assertIs<ArchiveDecode.Ok>(decodeArchive(encodeArchive(snapshot())))
            .snapshot
            .refuels
            .first { it.refuel.id == "refuel-1" }
            .refuel
            .pricePerLiter

        assertEquals(BigDecimal("6.190"), restored)
        assertEquals(3, requireNotNull(restored).scale())
    }

    @Test
    fun `an instant with nanoseconds survives the file`() {
        val precise = Instant.parse("2026-10-01T08:00:00.123456789Z")
        val original = snapshot().copy(
            exportedAt = precise,
            vehicles = listOf(vehicle("VIN1", "Corolla").copy(firstSeen = precise)),
        )

        val decoded = assertIs<ArchiveDecode.Ok>(decodeArchive(encodeArchive(original)))

        assertEquals(precise, decoded.snapshot.exportedAt)
        assertEquals(precise, decoded.snapshot.vehicles.single().firstSeen)
    }

    @Test
    fun `nan and infinity in the gps columns read back as null`() {
        val withNonFinite = fullTrip("trip-1", "VIN1").copy(
            startLat = Double.NaN,
            endLon = Double.POSITIVE_INFINITY,
            averageSpeed = Double.NEGATIVE_INFINITY,
        )

        val text = String(encodeArchive(snapshot().copy(trips = listOf(withNonFinite))), Charsets.UTF_8)
        val restored = assertIs<ArchiveDecode.Ok>(decode(text)).snapshot.trips.single()

        // JSON has no NaN or Infinity literal, so those three keys are null or absent - never a number.
        assertTrue("NaN" !in text && "Infinity" !in text, text)
        for (key in listOf("startLat", "endLon", "averageSpeed")) {
            assertTrue("\"$key\":null" in text || "\"$key\"" !in text, "$key must not hold a number: $text")
        }
        assertNull(restored.startLat)
        assertNull(restored.endLon)
        assertNull(restored.averageSpeed)
        assertEquals(54.3800, restored.endLat, "the columns that were fine stay")
    }

    @Test
    fun `a non finite distance is written as null and refused on the way back`() {
        val broken = bareTrip("trip-2", "VIN2").copy(trip = Trip("trip-2", TS, TS, Double.NaN, BigDecimal("0.200"), null))

        val text = String(encodeArchive(snapshot().copy(trips = listOf(broken))), Charsets.UTF_8)

        // `null` is the only JSON option; turning it into a distance would invent data.
        assertTrue("\"distanceKm\":null" in text)
        assertEquals(ArchiveDecode.Corrupt, decode(text))
    }

    @Test
    fun `another format or a newer version is refused without reading the body`() {
        assertEquals(ArchiveDecode.Unsupported, decode(MINIMAL.replace(ARCHIVE_FORMAT, "toyota-companion-sync")))
        assertEquals(ArchiveDecode.Unsupported, decode(MINIMAL.replace("\"version\":1", "\"version\":2")))
        assertEquals(ArchiveDecode.Unsupported, decode(MINIMAL.replace("\"version\":1", "\"version\":99")))

        // The same file with a header this app knows is fine, so only the header decided.
        assertEquals(EMPTY_SNAPSHOT, requireNotNull(decode(MINIMAL) as? ArchiveDecode.Ok).snapshot)
    }

    @Test
    fun `an empty, truncated or non json file is corrupt`() {
        assertEquals(ArchiveDecode.Corrupt, decodeArchive(ByteArray(0)))
        assertEquals(ArchiveDecode.Corrupt, decode("{}"))
        assertEquals(ArchiveDecode.Corrupt, decode("[]"))
        assertEquals(ArchiveDecode.Corrupt, decode("not json at all"))
        assertEquals(ArchiveDecode.Corrupt, decode(MINIMAL.take(MINIMAL.length / 2)))
    }

    @Test
    fun `a missing collection is corrupt`() {
        assertEquals(ArchiveDecode.Corrupt, decode(MINIMAL.replace("\"trips\":[],", "")))
        assertEquals(ArchiveDecode.Corrupt, decode(MINIMAL.replace("\"exportedAt\":\"2026-10-05T06:00:00Z\",", "")))
    }

    @Test
    fun `an unknown enum name or an unreadable instant is corrupt`() {
        val unknownStatus = ONE_REFUEL.replace("\"status\":\"PENDING\"", """"status":"TANKED"""")
        assertEquals(ArchiveDecode.Corrupt, decode(envelopeWith(refuels = "[$unknownStatus]")))

        val unknownSource = ONE_REFUEL.replace(
            """"status":"PENDING"""",
            """"status":"PENDING","litersSource":"GUESS"""",
        )
        assertEquals(ArchiveDecode.Corrupt, decode(envelopeWith(refuels = "[$unknownSource]")))

        val badInstant = ONE_REFUEL.replace("2026-10-01T09:00:00Z", "yesterday")
        assertEquals(ArchiveDecode.Corrupt, decode(envelopeWith(refuels = "[$badInstant]")))
    }

    @Test
    fun `the file is a versioned archive and never carries a secret`() {
        val text = String(encodeArchive(snapshot()), Charsets.UTF_8)

        assertTrue("\"format\":\"$ARCHIVE_FORMAT\"" in text, text)
        assertTrue("\"version\":$ARCHIVE_VERSION" in text, text)
        for (forbidden in listOf("password", "token", "accessToken")) {
            assertTrue(forbidden !in text, "$forbidden leaked into the backup")
        }
    }

    @Test
    fun `keys this app does not know are ignored`() {
        val newerEnvelope = MINIMAL.replace(
            """"format":"$ARCHIVE_FORMAT"""",
            """"format":"$ARCHIVE_FORMAT","fromTheFuture":{"a":1}""",
        )
        assertEquals(EMPTY_SNAPSHOT, requireNotNull(decode(newerEnvelope) as? ArchiveDecode.Ok).snapshot)

        val newerRefuel = ONE_REFUEL.replace(
            """"status":"PENDING"""",
            """"status":"PENDING","note":"written by a newer app"""",
        )

        val decoded = assertIs<ArchiveDecode.Ok>(decode(envelopeWith(refuels = "[$newerRefuel]")))

        assertEquals(1, decoded.snapshot.refuels.size)
        assertEquals(RefuelStatus.PENDING, decoded.snapshot.refuels.single().refuel.status)
        assertEquals("VIN1", decoded.snapshot.refuels.single().vin)
    }

    @Test
    fun `the header constants describe this file`() {
        assertEquals("toyota-companion-archive", ARCHIVE_FORMAT)
        assertEquals(1, ARCHIVE_VERSION)
    }
}
