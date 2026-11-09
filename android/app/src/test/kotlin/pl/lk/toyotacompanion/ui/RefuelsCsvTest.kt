package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus

/**
 * The spreadsheet of the confirmed refuels (PRD F47, docs/SPEC.md §48): the §14.4 format,
 * Polish separators, CRLF, plain numbers and empty cells for what Toyota/the owner did not provide.
 * The byte order mark belongs to the caller that writes bytes, so this text has none.
 */
private const val CRLF = "\r\n"

private val UTC: ZoneId = ZoneOffset.UTC

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private val AT: Instant = Instant.parse("2026-10-29T14:05:00Z")

private fun refuel(
    id: String,
    ts: Instant = AT,
    odometerKm: Double = 12_345.6,
    levelBefore: Int = 20,
    levelAfter: Int = 80,
    liters: String? = "30.100",
    price: String? = "6.190",
    total: String? = "186.32",
    source: LitersSource? = LitersSource.RECEIPT,
    filledToFull: Boolean? = true,
): Refuel = Refuel(
    id = id,
    ts = ts,
    odometerKm = odometerKm,
    levelBefore = levelBefore,
    levelAfter = levelAfter,
    liters = liters?.let(::BigDecimal),
    pricePerLiter = price?.let(::BigDecimal),
    totalCost = total?.let(::BigDecimal),
    status = RefuelStatus.CONFIRMED,
    litersSource = source,
    filledToFull = filledToFull,
)

private fun headerLine(): String = Texts.CSV_REFUELS_HEADER.joinToString(";")

class RefuelsCsvTest {
    private var previousLanguage = AppLanguage.PL

    // The texts are global state; a test that leaves EN behind would break the Polish assertions (C26 D1).
    @Before
    fun usePolish() {
        previousLanguage = Lang.current
        Lang.current = AppLanguage.PL
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }
    @Test
    fun `the file starts with the header from the texts and holds no byte order mark`() {
        val text = refuelsCsv(listOf(refuel("r1")), UTC)

        assertEquals(9, Texts.CSV_REFUELS_HEADER.size)
        assertEquals(headerLine(), text.split(CRLF)[0])
        assertFalse(text.startsWith(CSV_BOM), "the BOM is added when the text becomes bytes")
        assertTrue(text.endsWith(CRLF), "RFC 4180 line end")
    }

    @Test
    fun `an empty list writes only the header`() {
        assertEquals(headerLine() + CRLF, refuelsCsv(emptyList(), UTC))
    }

    @Test
    fun `one row has plain numbers, the unit in the header and the local time`() {
        val row = refuelsCsv(listOf(refuel("r1")), UTC).split(CRLF)[1]

        assertEquals(
            "2026-10-29 14:05;12345,6;20;80;30,100;6,190;186,32;z paragonu;tak",
            row,
        )
    }

    @Test
    fun `a cost is only the stored total, never liters times price`() {
        val row = refuelsCsv(listOf(refuel("r1", total = null)), UTC).split(CRLF)[1]

        assertEquals("2026-10-29 14:05;12345,6;20;80;30,100;6,190;;z paragonu;tak", row)
    }

    @Test
    fun `numbers are rounded HALF_UP with a comma and no grouping`() {
        val row = refuelsCsv(
            listOf(
                refuel(
                    "r1",
                    odometerKm = 12_345.65,
                    liters = "14.190",
                    price = "6.005",
                    total = "1.005",
                ),
            ),
            UTC,
        ).split(CRLF)[1]

        assertEquals("2026-10-29 14:05;12345,7;20;80;14,190;6,005;1,01;z paragonu;tak", row)
    }

    @Test
    fun `rows are oldest first and ties go by id`() {
        val olderB = refuel("b", ts = AT.minusSeconds(3_600), odometerKm = 2.0)
        val olderA = refuel("a", ts = AT.minusSeconds(3_600), odometerKm = 1.0)
        val newest = refuel("c", ts = AT, odometerKm = 3.0)
        val rows = refuelsCsv(listOf(newest, olderB, olderA), UTC).split(CRLF).filter { it.isNotEmpty() }.drop(1)

        assertEquals(3, rows.size)
        assertTrue(rows[0].startsWith("2026-10-29 13:05;1,0;"))
        assertTrue(rows[1].startsWith("2026-10-29 13:05;2,0;"))
        assertTrue(rows[2].startsWith("2026-10-29 14:05;3,0;"))
    }

    @Test
    fun `a missing value stays empty, never zero`() {
        val row = refuelsCsv(
            listOf(refuel("r1", liters = null, price = null, total = null, source = null, filledToFull = null)),
            UTC,
        ).split(CRLF)[1]

        assertEquals("2026-10-29 14:05;12345,6;20;80;;;;;", row)
    }

    @Test
    fun `every liters source and both fill flags have their label`() {
        val rows = refuelsCsv(
            listOf(
                refuel("a", source = LitersSource.RECEIPT, filledToFull = true),
                refuel("b", source = LitersSource.DERIVED, filledToFull = false),
                refuel("c", source = LitersSource.ESTIMATE, filledToFull = null),
            ),
            UTC,
        ).split(CRLF).filter { it.isNotEmpty() }.drop(1)

        assertTrue(rows[0].endsWith(";z paragonu;tak"))
        assertTrue(rows[1].endsWith(";wyliczone z paragonu;nie"))
        assertTrue(rows[2].endsWith(";szacunek z czujnika;"))
    }

    @Test
    fun `the times follow the zone of the screen, including a day boundary`() {
        val row = refuelsCsv(listOf(refuel("r1")), WARSAW).split(CRLF)[1]
        assertEquals("2026-10-29 15:05;12345,6;20;80;30,100;6,190;186,32;z paragonu;tak", row)

        val acrossMidnight = refuelsCsv(
            listOf(refuel("r1", ts = Instant.parse("2026-10-29T23:30:00Z"))),
            WARSAW,
        ).split(CRLF)[1]
        assertTrue(acrossMidnight.startsWith("2026-10-30 00:30;"))
    }

    @Test
    fun `the content carries no vin and no refuel id`() {
        val text = refuelsCsv(listOf(refuel("refuel-secret-id")), UTC)

        assertTrue("VIN1" !in text)
        assertTrue("refuel-secret-id" !in text)
    }

    @Test
    fun `the text does not depend on the default locale`() {
        val rows = listOf(refuel("r1"))
        val expected = refuelsCsv(rows, WARSAW)
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("pl-PL"))) {
            val previous = Locale.getDefault()
            try {
                Locale.setDefault(locale)
                assertEquals(expected, refuelsCsv(rows, WARSAW), locale.toString())
            } finally {
                Locale.setDefault(previous)
            }
        }
    }

    @Test
    fun `a pending refuel never reaches the sheet`() {
        val pending = refuel("p").copy(status = RefuelStatus.PENDING)
        val text = refuelsCsv(listOf(refuel("a"), pending), UTC)
        val rows = text.split(CRLF).filter { it.isNotEmpty() }

        assertEquals(2, rows.size, "header and one confirmed row")
        assertTrue("2026-10-29 14:05;12345,6;" in rows[1])
    }

    @Test
    fun `the labels follow the chosen language`() {
        Lang.current = AppLanguage.EN
        try {
            val text = refuelsCsv(
                listOf(
                    refuel("a", source = LitersSource.RECEIPT, filledToFull = true),
                    refuel("b", source = LitersSource.DERIVED, filledToFull = false),
                    refuel("c", source = LitersSource.ESTIMATE, filledToFull = null),
                ),
                UTC,
            )
            val rows = text.split(CRLF).filter { it.isNotEmpty() }.drop(1)

            assertEquals(
                "Date;Odometer [km];Level before [%];Level after [%];Liters [l];Price [zł/l];Cost [zł];Liters source;Filled to full",
                text.split(CRLF)[0],
            )
            assertTrue(rows[0].endsWith(";from receipt;yes"))
            assertTrue(rows[1].endsWith(";calculated from receipt;no"))
            assertTrue(rows[2].endsWith(";sensor estimate;"))
        } finally {
            Lang.current = AppLanguage.PL
        }
    }

    @Test
    fun `the file name says what it holds and carries no vin`() {
        assertEquals("toyota-companion-tankowania-2026-10-31.csv", refuelsCsvFileName(LocalDate.of(2026, 10, 31)))
        assertEquals("toyota-companion-tankowania-2026-01-05.csv", refuelsCsvFileName(LocalDate.of(2026, 1, 5)))
    }
}
