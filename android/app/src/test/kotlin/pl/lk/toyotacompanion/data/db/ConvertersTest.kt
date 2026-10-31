package pl.lk.toyotacompanion.data.db

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.domain.RefuelStatus

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `BigDecimal keeps its scale`() {
        val trailing = BigDecimal("0.600")
        val plain = BigDecimal("0.6")

        val storedTrailing = converters.bigDecimalToString(trailing)
        assertEquals("0.600", storedTrailing)
        assertEquals("0.6", converters.bigDecimalToString(plain))

        val back = requireNotNull(converters.stringToBigDecimal(storedTrailing))
        assertEquals(3, back.scale())
        assertNotEquals(plain, back)
        assertEquals(trailing, back)
    }

    @Test
    fun `BigDecimal is written without exponent`() {
        assertEquals("100000", converters.bigDecimalToString(BigDecimal("1E+5")))
        assertEquals("0.0000001", converters.bigDecimalToString(BigDecimal("1E-7")))
    }

    @Test
    fun `Instant keeps milliseconds`() {
        val instant = Instant.parse("2026-10-01T10:00:00.123Z")

        val millis = requireNotNull(converters.instantToMillis(instant))

        assertEquals(instant.toEpochMilli(), millis)
        assertEquals(instant, converters.millisToInstant(millis))
    }

    @Test
    fun `null values stay null`() {
        assertNull(converters.bigDecimalToString(null))
        assertNull(converters.stringToBigDecimal(null))
        assertNull(converters.instantToMillis(null))
        assertNull(converters.millisToInstant(null))
        assertNull(converters.refuelStatusToString(null))
        assertNull(converters.stringToRefuelStatus(null))
    }

    @Test
    fun `enums are stored by name`() {
        assertEquals("CONFIRMED", converters.refuelStatusToString(RefuelStatus.CONFIRMED))
        assertEquals(RefuelStatus.PENDING, converters.stringToRefuelStatus("PENDING"))
    }

    @Test
    fun `sync error kind is stored by name and an unknown name reads as null`() {
        assertEquals("STORAGE", converters.syncErrorKindToString(SyncErrorKind.STORAGE))
        assertEquals(SyncErrorKind.STORAGE, converters.stringToSyncErrorKind("STORAGE"))
        assertEquals(SyncErrorKind.LOGIN, converters.stringToSyncErrorKind("LOGIN"))

        // A database written by a newer app must not crash the older one on read.
        assertNull(converters.stringToSyncErrorKind("SOMETHING_NEWER"))
        assertNull(converters.stringToSyncErrorKind(null))
    }

    @Test
    fun `schema file for version 1 is exported`() {
        val schema = File("schemas/pl.lk.toyotacompanion.data.db.AppDatabase/1.json")

        assertTrue(schema.isFile, "missing ${schema.absolutePath}")
    }
}
