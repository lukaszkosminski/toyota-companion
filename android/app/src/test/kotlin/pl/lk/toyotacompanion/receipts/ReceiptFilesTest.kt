package pl.lk.toyotacompanion.receipts

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PRD F59, docs/SPEC.md §59: the pure rules of the receipt folder (names, sizes, leftovers). */
class ReceiptFilesTest {
    private val photo = "2b1e6c3a-1111-4222-8333-944455556666.jpg"

    @Test
    fun `only a name the app gives is a receipt`() {
        assertTrue(isReceiptFileName(photo))
        assertFalse(isReceiptFileName("../$photo"))
        assertFalse(isReceiptFileName("tmp-$photo"))
        assertFalse(isReceiptFileName("2b1e6c3a-1111-4222-8333-944455556666.png"))
        assertFalse(isReceiptFileName("notes.txt"))
    }

    @Test
    fun `the sample size keeps the decoded side at least the limit and never upscales`() {
        assertEquals(1, sampleSize(3000, 2000))
        assertEquals(2, sampleSize(4000, 3000))
        assertEquals(4, sampleSize(8000, 6000))
        assertEquals(2, sampleSize(3300, 10))
        assertEquals(1, sampleSize(900, 600))
    }

    @Test
    fun `the stored size has the longer side at most the limit and keeps the ratio`() {
        assertEquals(1600 to 1200, scaledSize(4000, 3000))
        assertEquals(400 to 1600, scaledSize(800, 3200))
        assertEquals(1000 to 800, scaledSize(1000, 800))
        assertEquals(1 to 1, scaledSize(1, 1))
    }

    @Test
    fun `orphaned files are the receipt names no entry points to, nothing else`() {
        val other = "9f8e7d6c-1111-4222-8333-000011112222.jpg"
        val files = setOf(photo, other, "tmp-x", "notes.txt")
        assertEquals(setOf(other), orphanedReceipts(files, referenced = setOf(photo)))
    }

    @Test
    fun `missing files are the referenced names the folder does not hold`() {
        val gone = "9f8e7d6c-1111-4222-8333-000011112222.jpg"
        assertEquals(setOf(gone), missingReceipts(referenced = setOf(photo, gone), files = setOf(photo)))
        assertEquals(emptySet(), missingReceipts(referenced = emptySet(), files = setOf(photo)))
    }
}
