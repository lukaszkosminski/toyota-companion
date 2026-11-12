package pl.lk.toyotacompanion.ui

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** docs/SPEC.md §60: the name, the daily rule and the rotation of the automatic archive copy. */
class AutoBackupOpsTest {

    @Test
    fun `a copy is named after its day, the same day format as the manual export`() {
        assertEquals("toyota-companion-2026-11-11.json", autoCopyName(LocalDate.parse("2026-11-11")))
        assertEquals("toyota-companion-2026-01-05.json", autoCopyName(LocalDate.parse("2026-01-05")))
    }

    @Test
    fun `the day is read back from a copy name and a foreign name has none`() {
        assertEquals(LocalDate.parse("2026-11-11"), autoCopyDate("toyota-companion-2026-11-11.json"))
        assertNull(autoCopyDate("toyota-companion-trasy-2026-11-11.csv"), "the trip CSV is not a copy")
        assertNull(autoCopyDate("notes.json"))
        assertNull(autoCopyDate("toyota-companion-2026-11-11.json.bak"))
        assertNull(autoCopyDate("toyota-companion-2026-13-40.json"), "a day that does not exist")
        assertNull(autoCopyDate("toyota-companion-2026-11-11.json/../x.json"))
    }

    @Test
    fun `a copy a provider renamed with a duplicate suffix still belongs to the rotation`() {
        assertEquals(LocalDate.parse("2026-11-11"), autoCopyDate("toyota-companion-2026-11-11 (1).json"))
        assertNull(autoCopyDate("toyota-companion-2026-11-11 (x).json"))
        val names = (1..AUTO_COPY_KEEP).map { autoCopyName(LocalDate.parse("2026-11-%02d".format(it))) } +
            "toyota-companion-2026-11-01 (1).json"
        assertEquals(listOf("toyota-companion-2026-11-01 (1).json"), copiesToDelete(names))
    }

    @Test
    fun `today's copy is written once and only with a folder`() {
        val today = LocalDate.parse("2026-11-11")
        assertFalse(shouldCopyToday(hasFolder = false, lastCopy = null, today = today), "no folder, no copy")
        assertTrue(shouldCopyToday(hasFolder = true, lastCopy = null, today = today), "the first copy")
        assertFalse(shouldCopyToday(hasFolder = true, lastCopy = today, today = today), "one per day")
        assertTrue(shouldCopyToday(hasFolder = true, lastCopy = today.minusDays(1), today = today), "a new day")
        assertTrue(
            shouldCopyToday(hasFolder = true, lastCopy = today.plusDays(1), today = today),
            "a copy dated in the future (clock moved back) does not block today",
        )
    }

    @Test
    fun `only the newest copies stay, foreign files are never touched`() {
        val names = (1..12).map { day -> autoCopyName(LocalDate.parse("2026-11-%02d".format(day))) } +
            listOf("notes.txt", "toyota-companion-trasy-2026-11-11.csv")
        val toDelete = copiesToDelete(names)
        assertEquals(listOf("toyota-companion-2026-11-01.json", "toyota-companion-2026-11-02.json"), toDelete.sorted())
        assertFalse(toDelete.any { it.endsWith(".csv") || it == "notes.txt" }, "only copies of this app")
    }

    @Test
    fun `a folder with fewer copies than the limit keeps all of them`() {
        val names = (1..3).map { autoCopyName(LocalDate.parse("2026-11-0$it")) }
        assertTrue(copiesToDelete(names).isEmpty())
        assertTrue(copiesToDelete(emptyList()).isEmpty())
    }

    @Test
    fun `exactly the limit keeps everything`() {
        val names = (1..AUTO_COPY_KEEP).map { autoCopyName(LocalDate.parse("2026-11-%02d".format(it))) }
        assertEquals(AUTO_COPY_KEEP, names.size)
        assertTrue(copiesToDelete(names).isEmpty())
    }

    @Test
    fun `the copy just written is never deleted, even among newer dated files`() {
        // A clock moved back: ten copies dated after "today" already sit in the folder.
        val future = (1..10).map { autoCopyName(LocalDate.parse("2026-12-%02d".format(it))) }
        val today = autoCopyName(LocalDate.parse("2026-11-11"))
        val toDelete = copiesToDelete(future + today, justWritten = today)
        assertFalse(today in toDelete, "today's copy stays")
        assertEquals(listOf("toyota-companion-2026-12-01.json"), toDelete, "the oldest of the others makes room")
    }

    @Test
    fun `with nothing to keep only the copy just written stays`() {
        val names = (1..3).map { autoCopyName(LocalDate.parse("2026-11-0$it")) }
        assertEquals(names.sorted(), copiesToDelete(names, keep = 0).sorted())
        assertEquals(names.take(2).sorted(), copiesToDelete(names, keep = 0, justWritten = names[2]).sorted())
    }
}
