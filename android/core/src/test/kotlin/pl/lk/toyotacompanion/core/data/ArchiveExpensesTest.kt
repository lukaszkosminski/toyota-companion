package pl.lk.toyotacompanion.core.data

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArchiveExpensesTest {
    private val expense = ArchiveExpense("e1", 20_400L, "TYRES", BigDecimal("1234.50"), "komplet zimowych")

    private fun snapshot(expenses: List<ArchiveExpense>) =
        ArchiveSnapshot(Instant.parse("2026-11-03T10:00:00Z"), emptyList(), emptyList(), emptyList(), emptyList(), expenses)

    @Test
    fun expensesSurviveTheRoundTrip() {
        val decoded = decodeArchive(encodeArchive(snapshot(listOf(expense))))
        assertIs<ArchiveDecode.Ok>(decoded)
        assertEquals(listOf(expense), decoded.snapshot.expenses)
    }

    @Test
    fun aFileWithoutExpensesStillReads() {
        val decoded = decodeArchive(encodeArchive(snapshot(emptyList())))
        assertIs<ArchiveDecode.Ok>(decoded)
        assertEquals(emptyList(), decoded.snapshot.expenses)
    }
}
