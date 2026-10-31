@file:OptIn(kotlin.test.ExperimentalKotlinTestApi::class)

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
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

/**
 * The spreadsheet of the trips (PRD F6, docs/SPEC.md §14.4): Polish separators, CRLF,
 * plain numbers, the same rounding as the list and the same empty cells as "brak ceny".
 * The byte order mark belongs to the caller that writes bytes, so this text has none.
 */
private const val CRLF = "\r\n"

private val UTC: ZoneId = ZoneOffset.UTC

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

private val START: Instant = Instant.parse("2026-10-29T14:05:00Z")

private fun trip(
    id: String,
    start: Instant = START,
    minutes: Long = 60,
    distanceKm: Double = 12.3,
    fuelL: String = "0.600",
    evKm: Double? = 3.2,
): TripRecord = TripRecord(
    trip = Trip(id, start, start.plusSeconds(minutes * 60), distanceKm, BigDecimal(fuelL), evKm),
    vin = "VIN1",
)

private fun stored(record: TripRecord, cost: TripCost? = null): StoredTrip = StoredTrip(record, cost)

private fun refuelCost(id: String, price: String = "6.190", costPln: String = "3.68"): TripCost =
    TripCost(id, BigDecimal(costPln), BigDecimal(price), "r1", CostSource.REFUEL)

private fun defaultCost(id: String, price: String = "6.19"): TripCost =
    TripCost(id, BigDecimal("3.68"), BigDecimal(price), null, CostSource.DEFAULT)

/** The three shapes of "no price" the list treats the same way (TripsGrouping.hasNoCost). */
private val NO_PRICE: List<TripCost?> = listOf(
    null,
    TripCost("t1", null, null, null, CostSource.NONE),
    TripCost("t1", null, BigDecimal("6.190"), "r1", CostSource.REFUEL),
)

private fun rowOf(trips: List<StoredTrip>, zone: ZoneId = UTC, index: Int = 1): String =
    tripsCsv(trips, zone).split(CRLF)[index]

private fun headerLine(): String = Texts.CSV_HEADER.joinToString(";")

class TripsCsvTest {
    @Test
    fun `the file starts with the header from the texts and holds no byte order mark`() {
        val text = tripsCsv(listOf(stored(trip("t1"), refuelCost("t1"))), UTC)

        assertEquals(8, Texts.CSV_HEADER.size)
        assertEquals(headerLine(), text.split(CRLF)[0])
        assertFalse(text.startsWith(CSV_BOM), "the BOM is added when the text becomes bytes")
        assertTrue(text.endsWith(CRLF), "RFC 4180 line end")
    }

    @Test
    fun `an empty list writes only the header`() {
        assertEquals(headerLine() + CRLF, tripsCsv(emptyList(), UTC))
    }

    @Test
    fun `rows go from the oldest trip and a tie takes the smaller id`() {
        val newest = stored(trip("c", Instant.parse("2026-10-03T08:00:00Z"), distanceKm = 30.0))
        val olderB = stored(trip("b", Instant.parse("2026-10-01T08:00:00Z"), minutes = 60, distanceKm = 5.0))
        val olderA = stored(trip("a", Instant.parse("2026-10-01T08:00:00Z"), minutes = 120, distanceKm = 7.0))

        val rows = tripsCsv(listOf(newest, olderB, olderA), UTC).split(CRLF).filter { it.isNotEmpty() }.drop(1)

        assertEquals(3, rows.size)
        assertTrue(rows[0].startsWith("2026-10-01 08:00;2026-10-01 10:00;7,0"), "id a first: ${rows[0]}")
        assertTrue(rows[1].startsWith("2026-10-01 08:00;2026-10-01 09:00;5,0"), "then id b: ${rows[1]}")
        assertTrue(rows[2].startsWith("2026-10-03 08:00"), rows[2])
    }

    @Test
    fun `a trip priced by a refuel shows the full price and names the refuel`() {
        val row = rowOf(listOf(stored(trip("t1"), refuelCost("t1", price = "6.190", costPln = "3.68"))))

        assertEquals(
            listOf("2026-10-29 14:05", "2026-10-29 15:05", "12,3", "0,60", "3,2", "6,190", "3,68", Texts.CSV_SOURCE_REFUEL)
                .joinToString(";"),
            row,
        )
    }

    @Test
    fun `the price always gets three places`() {
        val row = rowOf(listOf(stored(trip("t1"), refuelCost("t1", price = "6.19"))))

        assertTrue("6,190" in row, row)
    }

    @Test
    fun `the default price is named as the default price`() {
        val row = rowOf(listOf(stored(trip("t1"), defaultCost("t1"))))

        assertTrue(row.endsWith(Texts.CSV_SOURCE_DEFAULT), row)
    }

    @Test
    fun `every shape of a missing price leaves the last three cells empty`() {
        for (cost in NO_PRICE) {
            val row = rowOf(listOf(stored(trip("t1"), cost)))

            assertEquals(
                listOf("2026-10-29 14:05", "2026-10-29 15:05", "12,3", "0,60", "3,2", "", "", "").joinToString(";"),
                row,
                "for ${cost?.let { "${it.source} cost=${it.costPln}" } ?: "no cost row at all"}",
            )
        }
    }

    @Test
    fun `a trip without an EV distance keeps the cell empty`() {
        val row = rowOf(listOf(stored(trip("t1", evKm = null), refuelCost("t1"))))

        assertEquals(
            listOf("2026-10-29 14:05", "2026-10-29 15:05", "12,3", "0,60", "", "6,190", "3,68", Texts.CSV_SOURCE_REFUEL)
                .joinToString(";"),
            row,
        )
    }

    @Test
    fun `numbers round half up like the trips list`() {
        val row = rowOf(
            listOf(
                stored(
                    trip("t1", distanceKm = 12.25, fuelL = "0.605", evKm = 2.35),
                    refuelCost("t1", price = "6.1905", costPln = "3.675"),
                ),
            ),
        )

        assertEquals(
            listOf("2026-10-29 14:05", "2026-10-29 15:05", "12,3", "0,61", "2,4", "6,191", "3,68", Texts.CSV_SOURCE_REFUEL)
                .joinToString(";"),
            row,
        )
    }

    @Test
    fun `the sheet does not depend on the default locale`() {
        val rows = listOf(
            stored(
                trip("t1", distanceKm = 1234.5, fuelL = "1234.567", evKm = 0.05),
                refuelCost("t1", price = "1234.567", costPln = "1234.567"),
            ),
        )
        val expected = tripsCsv(rows, WARSAW)

        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ROOT, Locale.US, Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("ar"))) {
                Locale.setDefault(locale)
                assertEquals(expected, tripsCsv(rows, WARSAW), locale.toString())
                assertTrue("1234,57" in expected, expected)
                assertTrue("0,1" in expected, expected)
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `every line has as many cells as the header`() {
        val text = tripsCsv(listOf(stored(trip("t1"), refuelCost("t1")), stored(trip("t2", evKm = null))), UTC)

        for (line in text.split(CRLF).filter { it.isNotEmpty() }) {
            assertEquals(Texts.CSV_HEADER.size - 1, line.count { it == ';' }, line)
        }
    }

    @Test
    fun `the date carries the day of the phone zone`() {
        assertEquals("2026-01-05 09:07", csvDateTime(Instant.parse("2026-01-05T09:07:00Z"), UTC))
        // Warsaw is still on summer time here (UTC+2).
        assertEquals("2026-10-25 01:30", csvDateTime(Instant.parse("2026-10-24T23:30:00Z"), WARSAW))
        // Summer time ends on 25 October 2026, so this instant is already the next day there.
        assertEquals("2026-10-25 23:30", csvDateTime(Instant.parse("2026-10-25T23:30:00Z"), UTC))
        assertEquals("2026-10-26 00:30", csvDateTime(Instant.parse("2026-10-25T23:30:00Z"), WARSAW))
    }

    @Test
    fun `a cell is quoted only when it has to be`() {
        assertEquals("plain", csvCell("plain"))
        assertEquals("12,3", csvCell("12,3"))
        assertEquals("", csvCell(""))
        assertEquals("\"a;b\"", csvCell("a;b"))
        assertEquals("\"a\"\"b\"", csvCell("a\"b"))
        assertEquals("\"x\ny\"", csvCell("x\ny"))
        assertEquals("\"x\rY\"", csvCell("x\rY"))
    }

    @Test
    fun `text that looks like a formula gets an apostrophe and a number does not`() {
        assertEquals("'=SUM(A1)", csvCell("=SUM(A1)"))
        assertEquals("'+1", csvCell("+1"), "a leading plus is not a number here, so it is guarded")
        assertEquals("'@x", csvCell("@x"))
        assertEquals("'-1x", csvCell("-1x"))
        assertEquals("-1,5", csvCell("-1,5"), "a negative number stays a number the sheet can add")
        assertEquals("-1", csvCell("-1"))
    }

    @Test
    fun `the suggested file name carries the day and no vin`() {
        assertEquals("toyota-companion-trasy-2026-10-31.csv", tripsCsvFileName(LocalDate.of(2026, 10, 31)))
        assertEquals("toyota-companion-trasy-2026-01-05.csv", tripsCsvFileName(LocalDate.of(2026, 1, 5)))
    }
}
