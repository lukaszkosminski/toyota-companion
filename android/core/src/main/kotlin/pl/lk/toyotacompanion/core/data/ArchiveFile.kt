package pl.lk.toyotacompanion.core.data

import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.lk.toyotacompanion.core.domain.FuelReading
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus
import pl.lk.toyotacompanion.core.domain.Trip

/**
 * The local archive as one JSON file (PRD F7, docs/SPEC.md §14).
 *
 * The file is a plain backup of what Room holds at one moment: no merge, no partial
 * restore, never applied to a running database. Only [encodeArchive] and [decodeArchive]
 * are public, and the domain models stay free of `@Serializable` - the JSON shape is a
 * file format, so it can stay stable while the models change.
 *
 * Lossy types get primitive text, exactly like the Room converters (docs/SPEC.md §8.2):
 * - `Instant` as ISO-8601 (`2026-10-01T10:00:00.123456789Z`),
 * - `BigDecimal` as `toPlainString()`, so `6.190` keeps its scale and reads back equal,
 * - enums by their [Enum.name],
 * - a non-finite [Double] (`NaN`, `Infinity`) leaves the file as `null` - or as an absent
 *   key when the field is optional - because JSON has no literal for it, and reads back as
 *   `null`. Where the model has no `null` for that number, the row cannot be restored and
 *   is reported as [ArchiveDecode.Corrupt] rather than silently turned into a value; the
 *   archive in Room keeps its own data.
 *
 * No secret type (password, access or refresh token) exists here, so a backup cannot carry
 * one out (docs/SECURITY.md).
 */
const val ARCHIVE_FORMAT = "toyota-companion-archive"

/** Bumped on a breaking change of this shape; the newest version this app can read is [ARCHIVE_VERSION]. */
const val ARCHIVE_VERSION = 1

/** A refuel together with the vehicle it belongs to - the domain [Refuel] has no vin. */
data class ArchiveRefuel(val vin: String, val refuel: Refuel)

/** A telemetry reading together with the vehicle it belongs to. */
data class ArchiveReading(val vin: String, val reading: FuelReading)

/** Everything the archive holds at [exportedAt]. Costs are derived data and never exported. */
data class ArchiveSnapshot(
    val exportedAt: Instant,
    val vehicles: List<VehicleRecord>,
    val trips: List<TripRecord>,
    val refuels: List<ArchiveRefuel>,
    val readings: List<ArchiveReading>,
)

/**
 * Reading a backup file as an outcome and never as an exception: [Unsupported] means "not
 * a file this app can import" (foreign format, newer version), [Corrupt] means "this file
 * is damaged". Nothing is applied in either case (docs/SPEC.md §14.1).
 */
sealed class ArchiveDecode {
    data class Ok(val snapshot: ArchiveSnapshot) : ArchiveDecode()

    data object Unsupported : ArchiveDecode()

    data object Corrupt : ArchiveDecode()
}

/** The archive as UTF-8 JSON: the [ARCHIVE_FORMAT] header, then the four collections. */
fun encodeArchive(snapshot: ArchiveSnapshot): ByteArray =
    JSON.encodeToString(ArchiveFile.serializer(), snapshot.toDto()).toByteArray(Charsets.UTF_8)

/**
 * Reads a backup file. The header is checked before the body, so a newer or foreign file is
 * refused without mapping an archive nobody asked for. Everything the JSON layer, an enum
 * lookup or a number and date parse throws becomes [ArchiveDecode.Corrupt] - the caller
 * never sees an exception (only an out of memory error can still escape).
 *
 * The caller bounds the input size before calling: this reads a file the owner picked
 * (docs/SPEC.md §14.1).
 */
fun decodeArchive(bytes: ByteArray): ArchiveDecode {
    val root = try {
        JSON.parseToJsonElement(bytes.decodeToString())
    } catch (e: Exception) {
        return ArchiveDecode.Corrupt // empty, truncated, or not JSON at all
    }

    val header = root as? JsonObject ?: return ArchiveDecode.Corrupt
    val format = (header["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return ArchiveDecode.Corrupt
    val version = (header["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        ?: return ArchiveDecode.Corrupt

    if (format != ARCHIVE_FORMAT || version < 1L || version > ARCHIVE_VERSION) {
        return ArchiveDecode.Unsupported
    }

    return try {
        ArchiveDecode.Ok(JSON.decodeFromJsonElement(ArchiveFile.serializer(), root).toSnapshot())
    } catch (e: Exception) {
        ArchiveDecode.Corrupt
    }
}

// ---------------- file DTOs: private, so the file shape stays out of the domain ----------------

@Serializable
private data class ArchiveFile(
    val format: String,
    val version: Int,
    @Serializable(with = InstantText::class) val exportedAt: Instant,
    val vehicles: List<VehicleDto>,
    val trips: List<TripDto>,
    val refuels: List<RefuelDto>,
    val readings: List<ReadingDto>,
)

@Serializable
private data class VehicleDto(
    val vin: String,
    val brand: String? = null,
    val modelName: String? = null,
    val fuelType: String? = null,
    val evVehicle: Boolean? = null,
    @Serializable(with = InstantText::class) val firstSeen: Instant,
    @Serializable(with = InstantText::class) val lastSeen: Instant,
)

/** The domain [Trip] flattened next to the archive-only columns of [TripRecord] (PRD F5, F11-F13). */
@Serializable
private data class TripDto(
    val vin: String,
    val id: String,
    @Serializable(with = InstantText::class) val start: Instant,
    @Serializable(with = InstantText::class) val end: Instant,
    val distanceKm: Double?,
    @Serializable(with = PlainDecimal::class) val fuelL: BigDecimal,
    val evDistanceKm: Double? = null,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val durationIdleS: Long? = null,
    val averageSpeed: Double? = null,
    val maxSpeed: Double? = null,
    val nightTrip: Boolean? = null,
    val scoreGlobal: Int? = null,
    val scoreAcceleration: Int? = null,
    val scoreBraking: Int? = null,
    val scoreAdvice: Int? = null,
    val scoreConstantSpeed: Int? = null,
    val hdcEvDistanceM: Long? = null,
    val hdcEvTimeS: Long? = null,
    val hdcEcoDistM: Long? = null,
    val hdcEcoTimeS: Long? = null,
    val hdcPowerDistM: Long? = null,
    val hdcPowerTimeS: Long? = null,
    val hdcChargeDistM: Long? = null,
    val hdcChargeTimeS: Long? = null,
    @Serializable(with = InstantText::class) val syncedAt: Instant? = null,
)

@Serializable
private data class RefuelDto(
    val vin: String,
    val id: String,
    @Serializable(with = InstantText::class) val ts: Instant,
    val odometerKm: Double?,
    val levelBefore: Int,
    val levelAfter: Int,
    @Serializable(with = PlainDecimal::class) val liters: BigDecimal? = null,
    @Serializable(with = PlainDecimal::class) val pricePerLiter: BigDecimal? = null,
    @Serializable(with = PlainDecimal::class) val totalCost: BigDecimal? = null,
    @Serializable(with = RefuelStatusName::class) val status: RefuelStatus,
    @Serializable(with = LitersSourceName::class) val litersSource: LitersSource? = null,
    val filledToFull: Boolean? = null,
)

@Serializable
private data class ReadingDto(
    val vin: String,
    @Serializable(with = InstantText::class) val ts: Instant,
    val levelPct: Int,
    val odometerKm: Double?,
    val batteryLevelPct: Int? = null,
    val distanceToEmptyKm: Double? = null,
)

// ---------------- serializers for the two JDK types kotlinx does not know ----------------

private fun primitiveText(name: String): SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

/** `Instant` as ISO-8601 text, nanoseconds included when the value carries them. */
private object InstantText : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = primitiveText("pl.lk.toyotacompanion.ArchiveInstant")

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value))
    }
}

/** `BigDecimal` as plain text, so the stored scale survives the round trip (docs/SPEC.md §8.2). */
private object PlainDecimal : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = primitiveText("pl.lk.toyotacompanion.ArchiveDecimal")

    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }
}

private object RefuelStatusName : KSerializer<RefuelStatus> {
    private val byName = RefuelStatus.entries.associateBy { it.name }
    override val descriptor: SerialDescriptor = primitiveText("pl.lk.toyotacompanion.core.domain.RefuelStatus")

    override fun deserialize(decoder: Decoder): RefuelStatus = byName.named(decoder.decodeString(), "refuel status")

    override fun serialize(encoder: Encoder, value: RefuelStatus) {
        encoder.encodeString(value.name)
    }
}

private object LitersSourceName : KSerializer<LitersSource> {
    private val byName = LitersSource.entries.associateBy { it.name }
    override val descriptor: SerialDescriptor = primitiveText("pl.lk.toyotacompanion.core.domain.LitersSource")

    override fun deserialize(decoder: Decoder): LitersSource = byName.named(decoder.decodeString(), "liters source")

    override fun serialize(encoder: Encoder, value: LitersSource) {
        encoder.encodeString(value.name)
    }
}

// ---------------- mapping between the file and the models ----------------

private fun ArchiveSnapshot.toDto(): ArchiveFile = ArchiveFile(
    format = ARCHIVE_FORMAT,
    version = ARCHIVE_VERSION,
    exportedAt = exportedAt,
    vehicles = vehicles.map { it.toDto() },
    trips = trips.map { it.toDto() },
    refuels = refuels.map { it.toDto() },
    readings = readings.map { it.toDto() },
)

private fun ArchiveFile.toSnapshot(): ArchiveSnapshot = ArchiveSnapshot(
    exportedAt = exportedAt,
    vehicles = vehicles.map { it.toRecord() },
    trips = trips.map { it.toRecord() },
    refuels = refuels.map { it.toArchiveRefuel() },
    readings = readings.map { it.toArchiveReading() },
)

private fun VehicleRecord.toDto(): VehicleDto =
    VehicleDto(vin, brand, modelName, fuelType, evVehicle, firstSeen, lastSeen)

private fun VehicleDto.toRecord(): VehicleRecord =
    VehicleRecord(vin, brand, modelName, fuelType, evVehicle, firstSeen, lastSeen)

private fun TripRecord.toDto(): TripDto = TripDto(
    vin = vin,
    id = trip.id,
    start = trip.start,
    end = trip.end,
    distanceKm = trip.distanceKm.asWritable(),
    fuelL = trip.fuelL,
    evDistanceKm = trip.evDistanceKm.asWritable(),
    startLat = startLat.asWritable(),
    startLon = startLon.asWritable(),
    endLat = endLat.asWritable(),
    endLon = endLon.asWritable(),
    durationIdleS = durationIdleS,
    averageSpeed = averageSpeed.asWritable(),
    maxSpeed = maxSpeed.asWritable(),
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
)

private fun TripDto.toRecord(): TripRecord = TripRecord(
    trip = Trip(
        id = id,
        start = start,
        end = end,
        distanceKm = distanceKm.required("trips.distanceKm"),
        fuelL = fuelL,
        evDistanceKm = evDistanceKm,
    ),
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
)

private fun ArchiveRefuel.toDto(): RefuelDto = RefuelDto(
    vin = vin,
    id = refuel.id,
    ts = refuel.ts,
    odometerKm = refuel.odometerKm.asWritable(),
    levelBefore = refuel.levelBefore,
    levelAfter = refuel.levelAfter,
    liters = refuel.liters,
    pricePerLiter = refuel.pricePerLiter,
    totalCost = refuel.totalCost,
    status = refuel.status,
    litersSource = refuel.litersSource,
    filledToFull = refuel.filledToFull,
)

private fun RefuelDto.toArchiveRefuel(): ArchiveRefuel = ArchiveRefuel(
    vin = vin,
    refuel = Refuel(
        id = id,
        ts = ts,
        odometerKm = odometerKm.required("refuels.odometerKm"),
        levelBefore = levelBefore,
        levelAfter = levelAfter,
        liters = liters,
        pricePerLiter = pricePerLiter,
        totalCost = totalCost,
        status = status,
        litersSource = litersSource,
        filledToFull = filledToFull,
    ),
)

private fun ArchiveReading.toDto(): ReadingDto = ReadingDto(
    vin = vin,
    ts = reading.ts,
    levelPct = reading.levelPct,
    odometerKm = reading.odometerKm.asWritable(),
    batteryLevelPct = reading.batteryLevelPct,
    distanceToEmptyKm = reading.distanceToEmptyKm.asWritable(),
)

private fun ReadingDto.toArchiveReading(): ArchiveReading = ArchiveReading(
    vin = vin,
    reading = FuelReading(
        ts = ts,
        levelPct = levelPct,
        odometerKm = odometerKm.required("readings.odometerKm"),
        batteryLevelPct = batteryLevelPct,
        distanceToEmptyKm = distanceToEmptyKm,
    ),
)

// ---------------- helpers ----------------

private val JSON = Json {
    // A file from a newer app can carry keys this one does not know: skip them, keep the rest.
    ignoreUnknownKeys = true
}

/** Thrown while mapping a file; always caught by [decodeArchive] and reported as corrupt. */
private class ArchiveFileBroken(message: String) : IllegalArgumentException(message)

/** `NaN` and `Infinity` have no JSON literal, so they leave the file as `null`. */
private fun Double?.asWritable(): Double? = this?.takeIf { it.isFinite() }

/** A model number that must be a number: the file holds `null` or something not finite. */
private fun Double?.required(field: String): Double =
    this?.takeIf { it.isFinite() } ?: throw ArchiveFileBroken("'$field' must be a finite number")

private fun <T : Enum<T>> Map<String, T>.named(name: String, field: String): T =
    this[name] ?: throw ArchiveFileBroken("'$name' is not a known $field")
