@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

package pl.lk.toyotacompanion.ui

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith
import pl.lk.toyotacompanion.core.data.ARCHIVE_FORMAT
import pl.lk.toyotacompanion.core.data.ArchiveSnapshot
import pl.lk.toyotacompanion.core.data.encodeArchive

/**
 * The pure rules of the backup screen (PRD F8, docs/SPEC.md §14.3): when the phone asks for
 * a new copy, how the date is written, and how much of a chosen file is read.
 */
private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private val UTC: ZoneId = ZoneOffset.UTC

class BackupRulesTest {
    @Test
    fun `an archive without trips needs no copy`() {
        for (lastExport in listOf(null, Instant.parse("2020-01-01T00:00:00Z"))) {
            assertEquals(
                BackupReminder.None,
                backupReminder(lastExport = lastExport, now = Instant.parse("2026-10-31T06:00:00Z"), tripsStored = 0),
                "no trips means nothing to lose: $lastExport",
            )
        }
    }

    @Test
    fun `trips without any copy ask for one`() {
        assertEquals(
            BackupReminder.Never,
            backupReminder(lastExport = null, now = Instant.parse("2026-10-31T06:00:00Z"), tripsStored = 3),
        )
    }

    @Test
    fun `a copy of thirty days ago is still recent and thirty one is not`() {
        val now = Instant.parse("2026-10-31T06:00:00Z")

        assertEquals(BackupReminder.None, backupReminder(now.minusSeconds(30 * 86_400), now, tripsStored = 3))
        assertEquals(
            BackupReminder.Overdue(31),
            backupReminder(now.minusSeconds(31 * 86_400), now, tripsStored = 3),
        )
        assertEquals(
            BackupReminder.Overdue(400),
            backupReminder(now.minusSeconds(400 * 86_400), now, tripsStored = 3),
        )
    }

    @Test
    fun `a copy dated in the future counts as recent`() {
        val now = Instant.parse("2026-10-31T06:00:00Z")

        // A clock moved back after the copy must not nag the owner every day.
        assertEquals(BackupReminder.None, backupReminder(now.plusSeconds(86_400), now, tripsStored = 3))
    }

    @Test
    fun `the reminder counts whole days only`() {
        val now = Instant.parse("2026-10-31T06:00:00Z")

        // Thirty days and twenty three hours is still thirty whole days: no reminder yet.
        assertEquals(BackupReminder.None, backupReminder(now.minusSeconds(31 * 86_400 - 60), now, tripsStored = 3))
        assertEquals(BackupReminder.Overdue(31), backupReminder(now.minusSeconds(31 * 86_400 + 60), now, tripsStored = 3))
    }

    @Test
    fun `the date of a copy is a Polish day in the phone zone`() {
        assertEquals("31.10.2026", backupDate(Instant.parse("2026-10-31T00:30:00Z"), WARSAW))
        assertEquals("05.01.2026", backupDate(Instant.parse("2026-01-05T09:00:00Z"), WARSAW))
        assertEquals("01.01.2026", backupDate(Instant.parse("2026-01-01T00:00:00Z"), WARSAW))
    }

    @Test
    fun `around midnight the zone decides which day it was`() {
        val late = Instant.parse("2026-10-31T23:30:00Z")

        // Warsaw is already a day ahead here (summer time ended on 25 October 2026).
        assertEquals("31.10.2026", backupDate(late, UTC))
        assertEquals("01.11.2026", backupDate(late, WARSAW))
    }

    @Test
    fun `the import reads a file up to the limit and refuses one byte more`() {
        assertEquals(0, readArchiveBytes(ByteArrayInputStream(ByteArray(0)), limit = 3).size)
        assertEquals(3, readArchiveBytes(ByteArrayInputStream(ByteArray(3)), limit = 3).size)

        assertFailsWith<ArchiveTooLargeException> {
            readArchiveBytes(ByteArrayInputStream(ByteArray(4)), limit = 3)
        }
        assertFailsWith<ArchiveTooLargeException> {
            readArchiveBytes(ByteArrayInputStream(ByteArray(100_000)), limit = 3)
        }
    }

    @Test
    fun `the read keeps the bytes of the file`() {
        val bytes = encodeArchive(ArchiveSnapshot(Instant.parse("2026-10-05T06:00:00Z"), emptyList(), emptyList(), emptyList(), emptyList()))

        val read = readArchiveBytes(ByteArrayInputStream(bytes), limit = bytes.size)

        assertTrue(bytes.contentEquals(read))
        assertTrue(String(read, Charsets.UTF_8).startsWith("{\"format\":\"$ARCHIVE_FORMAT\""))
    }

    @Test
    fun `the limit is a megabyte scale cap`() {
        assertEquals(20 * 1024 * 1024, ARCHIVE_IMPORT_LIMIT_BYTES)
    }

    @Test
    fun `the suggested file name carries the day`() {
        assertEquals("toyota-companion-2026-10-31.json", archiveFileName(LocalDate.of(2026, 10, 31)))
        assertEquals("toyota-companion-2026-01-05.json", archiveFileName(LocalDate.of(2026, 1, 5)))
    }
}
