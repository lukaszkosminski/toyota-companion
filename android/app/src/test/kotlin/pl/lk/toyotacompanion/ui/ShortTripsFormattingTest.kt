package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.LengthBand
import pl.lk.toyotacompanion.core.domain.ShortTripSummary
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost
import pl.lk.toyotacompanion.core.domain.shortTripSummary

private val BASE: Instant = Instant.parse("2026-10-01T08:00:00Z")

private fun drive(index: Int, km: Double, fuel: String): Trip {
    val start = BASE.plusSeconds(index * 86_400L)
    return Trip("t$index", start, start.plusSeconds(600), km, BigDecimal(fuel), null)
}

private fun many(count: Int, km: Double, fuel: String, first: Int = 0): List<Trip> = (0 until count).map { drive(first + it, km, fuel) }

private fun priced(trips: List<Trip>, perTrip: String): List<TripCost> =
    trips.map { TripCost(it.id, BigDecimal(perTrip), null, "r", CostSource.REFUEL) }

/** The card of PRD F50 (docs/SPEC.md §50): the texts, the numbers with a no-break space and short lines that fit 360 dp. */
class ShortTripsFormattingTest {
    private var previousLanguage = AppLanguage.PL

    @Before
    fun usePolish() {
        previousLanguage = Lang.current
        Lang.current = AppLanguage.PL
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }

    /** 10 trips of 2 km with 0.2 l (10.00 l/100 km), 10 of 30 km with 1.2 l (4.00 l/100 km), priced 1.50 and 7.20 zł a trip. */
    private fun summary(withPrice: Boolean = true): ShortTripSummary {
        val short = many(10, 2.0, "0.2")
        val reference = many(10, 30.0, "1.2", first = 20)
        return shortTripSummary(short + reference, if (withPrice) priced(short, "1.50") + priced(reference, "7.20") else emptyList())
    }

    @Test
    fun `a band row says its litres per 100 km or that there are too few trips`() {
        val row = summary().bands.first { it.band == LengthBand.FROM_2 }
        assertEquals("10,00 l/100 km", ShortTripsFormatting.bandValue(row))
        val few = shortTripSummary(many(3, 3.0, "0.2"), emptyList()).bands.single()
        assertEquals("za mało", ShortTripsFormatting.bandValue(few))
    }

    @Test
    fun `the helper line of a band has the trips, the shares and the excess and stays short`() {
        val row = summary().bands.first { it.band == LengthBand.FROM_2 }
        // 10 of 20 trips and 20 of 320 km (6,25 % -> 6 %).
        assertEquals("10 tras (50 %) · 6 % km · +6,00 l/100 km", ShortTripsFormatting.bandLine(row))
        // A reference band has no excess in its line.
        val reference = summary().bands.first { it.band == LengthBand.FROM_25 }
        assertEquals("10 tras (50 %) · 94 % km", ShortTripsFormatting.bandLine(reference))
        // About 40 characters: one line at 360 dp (the review's rule for rows).
        assertTrue(ShortTripsFormatting.bandLine(row).length <= 46, ShortTripsFormatting.bandLine(row))
    }

    @Test
    fun `the helper line declines the trips in Polish and English`() {
        val one = shortTripSummary(many(1, 3.0, "0.2"), emptyList()).bands.single()
        assertTrue(ShortTripsFormatting.bandLine(one).startsWith("1 trasa (100 %)"))
        val two = shortTripSummary(many(2, 3.0, "0.2"), emptyList()).bands.single()
        assertTrue(ShortTripsFormatting.bandLine(two).startsWith("2 trasy"))
        val five = shortTripSummary(many(5, 3.0, "0.2"), emptyList()).bands.single()
        assertTrue(ShortTripsFormatting.bandLine(five).startsWith("5 tras"))
        Lang.current = AppLanguage.EN
        assertTrue(ShortTripsFormatting.bandLine(one).startsWith("1 trip (100 %)"))
        assertTrue(ShortTripsFormatting.bandLine(five).startsWith("5 trips"))
    }

    @Test
    fun `the headline and the excess line of the short bands`() {
        val s = summary()
        assertEquals("Krótkie trasy (poniżej 10 km): 50 % tras, 6 % km", ShortTripsFormatting.headline(s))
        // 1.2 l more, at 7.50 zl/l.
        assertEquals("Nadwyżka w archiwum: 1,2 l · 9,00 zł", ShortTripsFormatting.excessLine(s))
        assertEquals("Nadwyżka w archiwum: 1,2 l", ShortTripsFormatting.excessLine(summary(withPrice = false)))
        Lang.current = AppLanguage.EN
        assertEquals("Short trips (under 10 km): 50 % of trips, 6 % of km", ShortTripsFormatting.headline(s))
        assertEquals("Extra in the archive: 1,2 l · 9,00 zł", ShortTripsFormatting.excessLine(s))
    }

    @Test
    fun `short trips on the battery are not called an excess`() {
        val s = shortTripSummary(many(10, 2.0, "0") + many(10, 30.0, "1.2", first = 20), emptyList())
        assertEquals("Nie zużywają więcej paliwa niż trasy od 10 km", ShortTripsFormatting.excessLine(s))
    }

    @Test
    fun `without a comparison there is no excess line and without trips no headline`() {
        assertNull(ShortTripsFormatting.excessLine(shortTripSummary(many(10, 2.0, "0.2"), emptyList())))
        val empty = shortTripSummary(emptyList(), emptyList())
        assertNull(ShortTripsFormatting.headline(empty))
        assertNull(ShortTripsFormatting.excessLine(empty))
    }

    @Test
    fun `big numbers keep their no-break space`() {
        val trips = many(10, 2.0, "50") + many(10, 30.0, "1.2", first = 20)
        // 2 500 l per 100 km on the short band: the number is grouped with a no-break space.
        val row = shortTripSummary(trips, emptyList()).bands.first { it.band == LengthBand.FROM_2 }
        assertEquals("2\u00A0500,00 l/100 km", ShortTripsFormatting.bandValue(row))
    }

    @Test
    fun `the cold start block lists only the bands with enough trips on both sides`() {
        assertTrue(ShortTripsFormatting.coldLines(summary()).isEmpty())
        // 10 cold and 10 warm starts in one band: a cold trip every two days, a warm one half an hour after it.
        val cold = (0 until 10).map { Trip("c$it", BASE.plusSeconds(it * 172_800L), BASE.plusSeconds(it * 172_800L + 600), 3.0, BigDecimal("0.30"), null) }
        val warm = cold.mapIndexed { i, c -> Trip("w$i", c.end.plusSeconds(1_800), c.end.plusSeconds(2_400), 3.0, BigDecimal("0.15"), null) }
        val extra = Trip("x", BASE.plusSeconds(20 * 86_400L), BASE.plusSeconds(20 * 86_400L + 600), 3.0, BigDecimal("0.30"), null)
        val lines = ShortTripsFormatting.coldLines(shortTripSummary(cold + warm + extra, emptyList()))
        assertEquals(listOf("2–5 km: zimny 10,00 · ciepły 5,00 l/100 km (+5,00)"), lines)
        Lang.current = AppLanguage.EN
        assertEquals(listOf("2–5 km: cold 10,00 · warm 5,00 l/100 km (+5,00)"), ShortTripsFormatting.coldLines(shortTripSummary(cold + warm + extra, emptyList())))
    }

    @Test
    fun `the bars are scaled to the highest rate on the card`() {
        val s = summary()
        assertEquals(BigDecimal("10.00"), ShortTripsFormatting.highest(s))
        assertEquals(1f, ShortTripsFormatting.fraction(BigDecimal("10.00"), BigDecimal("10.00")))
        assertEquals(0.4f, ShortTripsFormatting.fraction(BigDecimal("4.00"), BigDecimal("10.00")), 0.0001f)
        assertEquals(0f, ShortTripsFormatting.fraction(null, BigDecimal("10.00")))
        assertEquals(0f, ShortTripsFormatting.fraction(BigDecimal("4.00"), BigDecimal.ZERO))
        assertEquals(BigDecimal.ZERO, ShortTripsFormatting.highest(shortTripSummary(emptyList(), emptyList())))
    }

    @Test
    fun `the band names and the hint are declined and translated`() {
        assertEquals("poniżej 2 km", Texts.shortBandName(LengthBand.UNDER_2))
        assertEquals("od 25 km", Texts.shortBandName(LengthBand.FROM_25))
        assertEquals("2–5 km", Texts.shortBandName(LengthBand.FROM_2))
        assertTrue(Texts.shortHint(3).contains("co najmniej 3 godziny"))
        assertTrue(Texts.shortHint(5).contains("5 godzin"))
        assertTrue(Texts.shortHint(1).contains("1 godzina"))
        Lang.current = AppLanguage.EN
        assertEquals("under 2 km", Texts.shortBandName(LengthBand.UNDER_2))
        assertEquals("25 km and more", Texts.shortBandName(LengthBand.FROM_25))
        assertTrue(Texts.shortHint(3).contains("at least 3 hours"))
        assertTrue(Texts.shortHint(1).contains("1 hour "))
    }

    @Test
    fun `the new texts differ between the languages and carry no private data`() {
        fun all() = listOf(
            Texts.SHORT_TITLE, Texts.shortHint(3), Texts.SHORT_TOO_FEW, Texts.SHORT_NO_EXCESS, Texts.SHORT_REFERENCE,
            Texts.SHORT_COLD_TITLE, Texts.SHORT_COLD_NONE, Texts.shortHeadline("38", "11"), Texts.shortExcess("14,2 l", "91,40 zł"),
            Texts.shortColdRow("2–5 km", "6,10", "5,60", "+0,50"), Texts.shortTripsOfShare(7, "20"),
        )
        val polish = all()
        Lang.current = AppLanguage.EN
        val english = all()
        for ((pl, en) in polish.zip(english)) {
            assertTrue(pl != en, "untranslated: $pl")
            for (forbidden in listOf("token", "VIN1", "p@ss", "secret")) assertFalse(forbidden in pl + en)
        }
        assertEquals("Krótkie trasy i zimny silnik", polish[0])
        assertEquals("Short trips and cold engine", english[0])
    }

    @Test
    fun `the statistics carry the short trip summary of the trips of the vehicle`() {
        val trips = many(6, 3.0, "0.2").map { StoredTrip(TripRecord(it, "VINAAA111"), null) }
        val view = computeStats(trips, ZoneOffset.UTC)
        assertEquals(6, view.shortTrips.usedTrips)
        assertEquals(LengthBand.FROM_2, view.shortTrips.bands.single().band)
        assertEquals(0, computeStats(emptyList(), ZoneOffset.UTC).shortTrips.usedTrips)
    }
}
