package pl.lk.toyotacompanion.data.db

import androidx.room3.ColumnTypeConverter
import java.math.BigDecimal
import java.time.Instant
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.RefuelStatus

/**
 * Column encodings (docs/SPEC.md §8.2):
 * - [BigDecimal] as TEXT via `toPlainString()`, so `0.600` and `0.6` stay distinct and no scale is lost;
 * - [Instant] as INTEGER epoch milliseconds;
 * - enums as TEXT `name`.
 * Money is never computed in SQL — only stored and read back.
 */
class Converters {
    @ColumnTypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @ColumnTypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)

    @ColumnTypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @ColumnTypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @ColumnTypeConverter
    fun refuelStatusToString(value: RefuelStatus?): String? = value?.name

    @ColumnTypeConverter
    fun stringToRefuelStatus(value: String?): RefuelStatus? = value?.let(RefuelStatus::valueOf)

    @ColumnTypeConverter
    fun litersSourceToString(value: LitersSource?): String? = value?.name

    @ColumnTypeConverter
    fun stringToLitersSource(value: String?): LitersSource? = value?.let(LitersSource::valueOf)

    @ColumnTypeConverter
    fun costSourceToString(value: CostSource?): String? = value?.name

    @ColumnTypeConverter
    fun stringToCostSource(value: String?): CostSource? = value?.let(CostSource::valueOf)

    @ColumnTypeConverter
    fun syncErrorKindToString(value: SyncErrorKind?): String? = value?.name

    @ColumnTypeConverter
    fun stringToSyncErrorKind(value: String?): SyncErrorKind? =
        // An unknown name (a newer app wrote it, then a downgrade) reads as "no kind", not a crash.
        value?.let { name -> SyncErrorKind.entries.firstOrNull { it.name == name } }
}
