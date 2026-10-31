package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import java.util.Locale
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost

private val UTC: ZoneId = ZoneOffset.UTC
private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/**
 * Polish formatting of the trips list (docs/SPEC.md §10.2). The rules exist
 * because `String.format("%.2f")` and `%02d` follow `Locale.getDefault()` — hence
 * the last test repeats the same assertions under other default locales.
 */
class TripsFormattingTest {
    @Test
    fun `money uses a comma and two places`() {
        assertEquals("3,68 zł", TripsFormatting.money(BigDecimal("3.68")))
        assertEquals("1234,56 zł", TripsFormatting.money(BigDecimal("1234.56")))
    }

    @Test
    fun `money rounds half up`() {
        assertEquals("3,68 zł", TripsFormatting.money(BigDecimal("3.675")))
        assertEquals("3,67 zł", TripsFormatting.money(BigDecimal("3.674")))
        assertEquals("0,01 zł", TripsFormatting.money(BigDecimal("0.005")))
    }

    @Test
    fun `zero money keeps two places`() {
        assertEquals("0,00 zł", TripsFormatting.money(BigDecimal.ZERO))
        assertEquals("0,00 zł", TripsFormatting.money(BigDecimal("0")))
    }

    @Test
    fun `price per liter carries the unit`() {
        assertEquals("6,14 zł/l", TripsFormatting.pricePerLiter(BigDecimal("6.14")))
        // The stored price has three places; the list shows two.
        assertEquals("6,14 zł/l", TripsFormatting.pricePerLiter(BigDecimal("6.140")))
        assertEquals("6,15 zł/l", TripsFormatting.pricePerLiter(BigDecimal("6.145")))
        assertEquals("6,14 zł/l", TripsFormatting.pricePerLiter(BigDecimal("6.1449")))
    }

    @Test
    fun `price line under the cost carries the default price note`() {
        assertNull(TripsFormatting.priceLine(fmtStored("t1", null)))
        assertNull(TripsFormatting.priceLine(fmtStored("t2", fmtCost("t2", null))))
        assertEquals("6,14 zł/l", TripsFormatting.priceLine(fmtStored("t3", fmtCost("t3", "6.1449"))))
        val def = fmtStored("t4", fmtCost("t4", "6.1449", CostSource.DEFAULT))
        assertEquals("6,14 zł/l · cena domyślna", TripsFormatting.priceLine(def))
        // The row says "brak ceny"; a price under it would contradict it.
        assertNull(TripsFormatting.priceLine(fmtStored("t5", fmtCost("t5", "6.14", CostSource.NONE))))
        val noAmount = fmtCost("t6", "6.14").copy(costPln = null)
        assertNull(TripsFormatting.priceLine(fmtStored("t6", noAmount)))
    }

    @Test
    fun `liters use two places`() {
        assertEquals("0,60 l", TripsFormatting.liters(BigDecimal("0.6")))
        assertEquals("25,80 l", TripsFormatting.liters(BigDecimal("25.800")))
    }

    @Test
    fun `distance uses one place`() {
        assertEquals("12,3 km", TripsFormatting.kilometers(12.34))
        assertEquals("12,4 km", TripsFormatting.kilometers(12.35))
        assertEquals("0,0 km", TripsFormatting.kilometers(0.0))
        assertEquals("128,4 km", TripsFormatting.kilometers(BigDecimal("128.44")))
    }

    @Test
    fun `start shows day month and time in the given zone`() {
        val at = Instant.parse("2026-10-29T14:05:00Z")

        assertEquals("29.10 14:05", TripsFormatting.startDateTime(at, UTC))
        assertEquals("29.10 15:05", TripsFormatting.startDateTime(at, WARSAW))
    }

    @Test
    fun `start crosses midnight into the next day`() {
        // 22:30 UTC on 30 September is 00:30 local on 1 October (CEST = UTC+2).
        assertEquals(
            "01.10 00:30",
            TripsFormatting.startDateTime(Instant.parse("2026-09-30T22:30:00Z"), WARSAW),
        )
    }

    @Test
    fun `month label is the nominative name with the year`() {
        assertEquals("październik 2026", TripsFormatting.monthLabel(MonthKey(2026, 10)))
        assertEquals("styczeń 2025", TripsFormatting.monthLabel(MonthKey(2025, 1)))
        assertEquals("grudzień 2027", TripsFormatting.monthLabel(MonthKey(2027, 12)))
    }

    @Test
    fun `there are twelve different month names`() {
        val names = TripsFormatting.monthNames()

        assertEquals(12, names.size)
        assertEquals(12, names.distinct().size, names.toString())
        for (name in names) {
            assertTrue(name.isNotBlank(), name)
            assertTrue(!name.any { it.isDigit() }, name)
        }
    }

    @Test
    fun `plain writes the same number without its unit`() {
        assertEquals("3,68", TripsFormatting.plain(BigDecimal("3.675"), 2))
        assertEquals("12,3", TripsFormatting.plain(BigDecimal("12.34"), 1))
        assertEquals("0,60", TripsFormatting.plain(BigDecimal("0.6"), 2))
        assertEquals("6,190", TripsFormatting.plain(BigDecimal("6.19"), 3))

        // The CSV and the list must round identically, so they share this one function.
        assertEquals(TripsFormatting.money(BigDecimal("3.675")).removeSuffix(" zł"), TripsFormatting.plain(BigDecimal("3.675"), 2))
        assertEquals(TripsFormatting.kilometers(BigDecimal("12.34")).removeSuffix(" km"), TripsFormatting.plain(BigDecimal("12.34"), 1))
    }

    @Test
    fun `formatting does not depend on the default locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ROOT, Locale.US, Locale.forLanguageTag("de-DE"))) {
                Locale.setDefault(locale)
                assertEquals("3,68 zł", TripsFormatting.money(BigDecimal("3.675")), locale.toString())
                assertEquals("6,14 zł/l", TripsFormatting.pricePerLiter(BigDecimal("6.14")), locale.toString())
                assertEquals("0,60 l", TripsFormatting.liters(BigDecimal("0.6")), locale.toString())
                assertEquals("12,3 km", TripsFormatting.kilometers(12.34), locale.toString())
                assertEquals("29.10 14:05", TripsFormatting.startDateTime(Instant.parse("2026-10-29T14:05:00Z"), UTC))
                assertEquals("październik 2026", TripsFormatting.monthLabel(MonthKey(2026, 10)), locale.toString())
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}

/** Start instant of the formatted trips; the zone tests above use their own instants. */
private val FMT_START: Instant = Instant.parse("2026-10-29T13:00:00Z")

/** Row for the formatting checks — the same shape `SyncEngine.trips()` returns. */
private fun fmtStored(id: String, cost: TripCost?): StoredTrip = StoredTrip(
    record = TripRecord(
        trip = Trip(
            id = id,
            start = FMT_START,
            end = FMT_START.plusSeconds(1_500),
            distanceKm = 12.3,
            fuelL = BigDecimal("0.6"),
            evDistanceKm = null,
        ),
        vin = "VINAAA111",
    ),
    cost = cost,
)

private fun fmtCost(tripId: String, price: String?, source: CostSource = CostSource.REFUEL): TripCost =
    TripCost(
        tripId = tripId,
        costPln = BigDecimal("3.68"),
        pricePerLiter = price?.let(::BigDecimal),
        refuelId = if (source == CostSource.REFUEL) "refuel-1" else null,
        source = source,
    )
