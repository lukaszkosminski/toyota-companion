package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.EconomyInterval
import pl.lk.toyotacompanion.core.domain.EconomyStatus
import pl.lk.toyotacompanion.core.domain.LitersSource
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus

private val TS = java.time.Instant.parse("2026-10-29T13:00:00Z")

/** A log row with the liters the confirm form left behind. */
private fun loggedRefuel(
    liters: String?,
    source: LitersSource?,
    filledToFull: Boolean? = null,
): Refuel = Refuel(
    id = "r1",
    ts = TS,
    odometerKm = 12_345.0,
    levelBefore = 20,
    levelAfter = 90,
    liters = liters?.let { BigDecimal(it) },
    pricePerLiter = BigDecimal("6.190"),
    totalCost = null,
    status = RefuelStatus.CONFIRMED,
    litersSource = source,
    filledToFull = filledToFull,
)

/** An interval of the shape `fuelEconomyIntervals` returns for the given status. */
private fun intervalOf(
    status: EconomyStatus,
    distance: String = "600.0",
    ev: String = "0.0",
    computer: String = "27.000",
    odometer: String? = "600.0",
    computerPer100: String? = "4.50",
    actualPer100: String? = "5.00",
    difference: String? = "11.1",
): EconomyInterval = EconomyInterval(
    startRefuelId = "a",
    endRefuelId = if (status == EconomyStatus.OPEN) null else "b",
    status = status,
    trips = 3,
    distanceKm = BigDecimal(distance),
    evDistanceKm = BigDecimal(ev),
    computerLiters = BigDecimal(computer),
    actualLiters = if (status in listOf(EconomyStatus.OK, EconomyStatus.TOO_SHORT)) BigDecimal("30.000") else null,
    odometerKm = odometer?.let { BigDecimal(it) },
    computerLPer100Km = computerPer100?.let { BigDecimal(it) },
    actualLPer100Km = actualPer100?.let { BigDecimal(it) },
    differencePct = difference?.let { BigDecimal(it) },
)

/**
 * The Polish of the refuel log (docs/SPEC.md §15.2). Every number goes through
 * `TripsFormatting.plain`, so the last test repeats the assertions under other default
 * locales — `litersLine` also lowercases a [Texts] constant, which is locale work.
 */
class RefuelLogFormattingTest {

    @Test
    fun `consumption shows two places and the unit`() {
        assertEquals("4,50 l/100 km", RefuelLogFormatting.consumption(BigDecimal("4.5")))
        assertEquals("4,82 l/100 km", RefuelLogFormatting.consumption(BigDecimal("4.82")))
        // Half up, like every other number in this app.
        assertEquals("4,51 l/100 km", RefuelLogFormatting.consumption(BigDecimal("4.505")))
        assertEquals("4,50 l/100 km", RefuelLogFormatting.consumption(BigDecimal("4.504")))
    }

    @Test
    fun `difference carries a plus, a real minus or nothing`() {
        assertEquals("+11,1 %", RefuelLogFormatting.difference(BigDecimal("11.1")))
        assertEquals("\u221211,1 %", RefuelLogFormatting.difference(BigDecimal("-11.1")))
        assertEquals("0,0 %", RefuelLogFormatting.difference(BigDecimal.ZERO))
        assertEquals("+2,1 %", RefuelLogFormatting.difference(BigDecimal("2.05")))
        // A hyphen or an ASCII dash would not match the Polish typography of the rest.
        assertEquals('\u2212', RefuelLogFormatting.difference(BigDecimal("-0.1")).first())
    }

    @Test
    fun `distance shows one place`() {
        assertEquals("600,0 km", RefuelLogFormatting.distance(BigDecimal("600")))
        assertEquals("612,4 km", RefuelLogFormatting.distance(BigDecimal("612.44")))
        assertEquals("612,5 km", RefuelLogFormatting.distance(BigDecimal("612.45")))
    }

    @Test
    fun `liters line names where the liters came from and the full tank`() {
        val receipt = loggedRefuel("30.000", LitersSource.RECEIPT, filledToFull = true)
        assertEquals(
            RefuelFormatting.liters(BigDecimal("30.000")) + " " + Texts.LOG_SOURCE_RECEIPT +
                Texts.SEPARATOR + Texts.REFUEL_FILLED_FULL.lowercase(),
            RefuelLogFormatting.litersLine(receipt),
        )
        // The pinned shape guards against swapping the source words or the separator.
        assertEquals("30 l z paragonu \u00B7 do pe\u0142na", RefuelLogFormatting.litersLine(receipt))

        val derived = loggedRefuel("30.000", LitersSource.DERIVED)
        assertEquals(
            RefuelFormatting.liters(BigDecimal("30.000")) + " " + Texts.LOG_SOURCE_DERIVED,
            RefuelLogFormatting.litersLine(derived),
        )
        val estimate = loggedRefuel("30.000", LitersSource.ESTIMATE)
        assertEquals(
            RefuelFormatting.liters(BigDecimal("30.000")) + " " + Texts.LOG_SOURCE_ESTIMATE,
            RefuelLogFormatting.litersLine(estimate),
        )
    }

    @Test
    fun `liters line without a source or without a full tank says less`() {
        // confirmRefuel always sets a source with liters, so this is the defensive path.
        assertEquals(
            "30 l",
            RefuelLogFormatting.litersLine(loggedRefuel("30.000", null)),
        )
        assertEquals(
            "30 l" + Texts.SEPARATOR + Texts.REFUEL_FILLED_FULL.lowercase(),
            RefuelLogFormatting.litersLine(loggedRefuel("30.000", null, filledToFull = true)),
        )
        // "Not filled to full" is not said out loud; only the full tank is.
        assertEquals(
            "30 l z paragonu",
            RefuelLogFormatting.litersLine(loggedRefuel("30.000", LitersSource.RECEIPT, filledToFull = false)),
        )
        assertEquals(
            "30 l z paragonu",
            RefuelLogFormatting.litersLine(loggedRefuel("30.000", LitersSource.RECEIPT, filledToFull = null)),
        )
    }

    @Test
    fun `liters line is missing without liters`() {
        assertNull(RefuelLogFormatting.litersLine(loggedRefuel(null, null)))
        // A pending entry has no liters yet, so the log shows only the time and the levels.
        assertNull(RefuelLogFormatting.litersLine(loggedRefuel(null, LitersSource.RECEIPT, filledToFull = true)))
    }

    @Test
    fun `the economy line compares when the interval is ok`() {
        val interval = intervalOf(EconomyStatus.OK)

        assertEquals(
            Texts.economyCompared(
                RefuelLogFormatting.consumption(BigDecimal("5.00")),
                RefuelLogFormatting.consumption(BigDecimal("4.50")),
                RefuelLogFormatting.difference(BigDecimal("11.1")),
            ),
            RefuelLogFormatting.economyLine(interval),
        )
    }

    @Test
    fun `an ok interval without the numbers says there were no trips`() {
        for (broken in listOf(
            intervalOf(EconomyStatus.OK, actualPer100 = null),
            intervalOf(EconomyStatus.OK, computerPer100 = null),
            intervalOf(EconomyStatus.OK, difference = null),
        )) {
            assertEquals(Texts.ECONOMY_NO_TRIPS, RefuelLogFormatting.economyLine(broken))
        }
    }

    @Test
    fun `every other status says why there is no comparison`() {
        val expected = mapOf(
            EconomyStatus.OPEN to Texts.ECONOMY_OPEN,
            EconomyStatus.PENDING_REFUEL to Texts.ECONOMY_PENDING,
            EconomyStatus.DISMISSED_REFUEL to Texts.ECONOMY_DISMISSED,
            EconomyStatus.MISSING_RECEIPT to Texts.ECONOMY_MISSING_RECEIPT,
            EconomyStatus.NO_TRIPS to Texts.ECONOMY_NO_TRIPS,
            EconomyStatus.TOO_SHORT to Texts.ECONOMY_TOO_SHORT,
        )
        for ((status, text) in expected) {
            // An unfinished interval never shows the actual side.
            assertEquals(text, RefuelLogFormatting.economyLine(intervalOf(status)), status.name)
        }
    }

    @Test
    fun `the detail line lists what the interval knows`() {
        val onlyTrips = intervalOf(EconomyStatus.OK, odometer = null)
        assertEquals(Texts.economyTrips("600,0 km"), RefuelLogFormatting.detailLine(onlyTrips))

        val withOdometer = intervalOf(EconomyStatus.OK)
        assertEquals(
            Texts.economyTrips("600,0 km") + Texts.SEPARATOR + Texts.economyOdometer("600,0 km"),
            RefuelLogFormatting.detailLine(withOdometer),
        )

        val hybrid = intervalOf(EconomyStatus.OK, ev = "70.25")
        assertEquals(
            Texts.economyTrips("600,0 km") + Texts.SEPARATOR + Texts.economyOdometer("600,0 km") +
                Texts.SEPARATOR + Texts.economyEv("70,3 km"),
            RefuelLogFormatting.detailLine(hybrid),
        )
        // The full line as the user reads it.
        assertEquals(
            "600,0 km tras \u00B7 licznik 600,0 km \u00B7 w tym EV 70,3 km",
            RefuelLogFormatting.detailLine(hybrid),
        )
    }

    @Test
    fun `a zero ev distance is not mentioned`() {
        assertEquals(
            Texts.economyTrips("600,0 km"),
            RefuelLogFormatting.detailLine(intervalOf(EconomyStatus.OK, odometer = null, ev = "0.0")),
        )
        assertEquals(
            Texts.economyTrips("600,0 km"),
            RefuelLogFormatting.detailLine(intervalOf(EconomyStatus.OK, odometer = null, ev = "0")),
        )
    }

    @Test
    fun `the open interval adds the computer so far`() {
        val open = intervalOf(EconomyStatus.OPEN, odometer = null)
        assertEquals(
            Texts.economyTrips("600,0 km") + Texts.SEPARATOR +
                Texts.economyComputerSoFar("4,50 l/100 km"),
            RefuelLogFormatting.detailLine(open),
        )
        // Without any trip there is no computer figure to show.
        val noTrips = intervalOf(EconomyStatus.OPEN, odometer = null, computerPer100 = null, distance = "0.0")
        assertEquals(Texts.economyTrips("0,0 km"), RefuelLogFormatting.detailLine(noTrips))
        // A closed interval never says it — the word "komputer" lives in the comparison line.
        val closed = RefuelLogFormatting.detailLine(intervalOf(EconomyStatus.OK))
        assertFalse(Texts.economyComputerSoFar("4,50 l/100 km") in closed, closed)
    }

    @Test
    fun `formatting does not depend on the default locale`() {
        // Captured while the default locale is still the JVM one: the loop must not move it.
        val filledFull = Texts.REFUEL_FILLED_FULL.lowercase(Locale.ROOT)
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(
                Locale.ROOT,
                Locale.US,
                Locale.forLanguageTag("de-DE"),
                // Turkish rewrites the case of dotted I, the only letter the log lowercases.
                Locale.forLanguageTag("tr-TR"),
            )) {
                Locale.setDefault(locale)
                val reason = locale.toString()
                assertEquals("4,50 l/100 km", RefuelLogFormatting.consumption(BigDecimal("4.5")), reason)
                assertEquals("+11,1 %", RefuelLogFormatting.difference(BigDecimal("11.1")), reason)
                assertEquals("\u221211,1 %", RefuelLogFormatting.difference(BigDecimal("-11.1")), reason)
                assertEquals("0,0 %", RefuelLogFormatting.difference(BigDecimal.ZERO), reason)
                assertEquals("600,0 km", RefuelLogFormatting.distance(BigDecimal("600")), reason)
                assertEquals(
                    "30 l z paragonu \u00B7 do pe\u0142na",
                    RefuelLogFormatting.litersLine(
                        loggedRefuel("30.000", LitersSource.RECEIPT, filledToFull = true),
                    ),
                    reason,
                )
                assertEquals(
                    RefuelFormatting.liters(BigDecimal("30.000")) + " " + Texts.LOG_SOURCE_RECEIPT +
                        Texts.SEPARATOR + filledFull,
                    RefuelLogFormatting.litersLine(
                        loggedRefuel("30.000", LitersSource.RECEIPT, filledToFull = true),
                    ),
                    reason,
                )
                assertEquals(
                    Texts.ECONOMY_OPEN,
                    RefuelLogFormatting.economyLine(intervalOf(EconomyStatus.OPEN)),
                    reason,
                )
                assertEquals(
                    "600,0 km tras \u00B7 w tym EV 70,3 km",
                    RefuelLogFormatting.detailLine(
                        intervalOf(EconomyStatus.OK, odometer = null, ev = "70.25"),
                    ),
                    reason,
                )
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
