package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.PeriodStats
import pl.lk.toyotacompanion.core.domain.Season
import pl.lk.toyotacompanion.core.domain.TripShare

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
private val SEEN: Instant = Instant.parse("2026-10-29T13:05:00Z")

private val SHARE_ROW = TripShare(
    tripId = "t",
    distanceKm = BigDecimal("12.0"),
    evDistanceKm = BigDecimal("7.4"),
    sharePct = BigDecimal("61.3"),
    inconsistent = true,
)

/** A stop and a place with the full precision of the archive, so the sweep checks the rounding. */
private val STOP = ParkingStop(SEEN, SEEN.plusSeconds(63_300), 54.3724, 18.6381)
private val PLACE = ParkingPlace(
    lat = 54.37249183746291,
    lon = 18.63810294736281,
    stops = 12,
    total = Duration.ofDays(3).plusHours(4),
    lastStop = SEEN,
)

/** A period row with every optional value filled, so the sweep sees every rendered number. */
private fun fullPeriod() = PeriodStats(
    year = 2026,
    season = Season.WINTER,
    trips = 12,
    distanceKm = BigDecimal("450.0"),
    fuelL = BigDecimal("20.250"),
    lPer100Km = BigDecimal("4.50"),
    costPln = BigDecimal("312.40"),
    tripsWithoutCost = 3,
    costPer100Km = BigDecimal("69.42"),
    evSharePct = BigDecimal("41.0"),
    averageTripKm = BigDecimal("37.5"),
)

private fun noCostPeriod() = fullPeriod().copy(costPln = null, costPer100Km = null, evSharePct = null)

/**
 * The C11 screens in the "no secrets" sweep of TextsTest (docs/SECURITY.md): every text and every
 * rendered value of "Statystyki" and "Postoje". Kept in its own file so the C11 round does not edit
 * TextsTest; folding these lists into that sweep is the owner's call.
 */
class StatsParkingTextsTest {

    /** Everything the two screens can put on screen, constants and formatted values together. */
    private fun onScreen(): List<String> = listOf(
        // "Statystyki" (docs/SPEC.md sections 16 and 18).
        Texts.STATS_TITLE,
        Texts.STATS_EMPTY,
        Texts.STATS_READ_ERROR,
        Texts.STATS_EV_SECTION,
        Texts.STATS_NO_EV_DATA,
        Texts.STATS_CLIPPED_MARK,
        Texts.STATS_RANKING,
        Texts.STATS_YEAR_OVER_YEAR,
        Texts.STATS_YEARS,
        Texts.STATS_SEASONS,
        Texts.SEASON_WINTER,
        Texts.SEASON_SPRING,
        Texts.SEASON_SUMMER,
        Texts.SEASON_AUTUMN,
        Texts.statsEvTotal(StatsFormatting.share(BigDecimal("41.0"))),
        Texts.statsShareOfTrip(StatsFormatting.share(BigDecimal("61.3"))),
        Texts.statsTrips(12),
        Texts.statsLeftOut(4, 2),
        Texts.statsWindow(StatsFormatting.window(LocalDate.of(2026, 10, 31)), 2026, 2025),
        StatsFormatting.share(BigDecimal("41.0")),
        StatsFormatting.share(null),
        StatsFormatting.rankingRow(SHARE_ROW),
        StatsFormatting.periodLabel(fullPeriod()),
        StatsFormatting.periodLine(fullPeriod()),
        StatsFormatting.periodLine(noCostPeriod()),
        StatsFormatting.window(LocalDate.of(2026, 3, 5)),
        // "Postoje" (docs/SPEC.md section 18).
        Texts.PARKING_TITLE,
        Texts.PARKING_EMPTY,
        Texts.PARKING_READ_ERROR,
        Texts.PARKING_PRIVACY,
        Texts.PARKING_PLACES,
        Texts.PARKING_RECENT,
        Texts.PARKING_NO_POSITION,
        Texts.parkingStops(12),
        Texts.parkingTotal(ParkingFormatting.duration(Duration.ofMinutes(185))),
        Texts.parkingLast(RefuelFormatting.time(SEEN, WARSAW)),
        Texts.parkingSkipped(3, 1),
        ParkingFormatting.place(54.37249183746291, 18.63810294736281),
        ParkingFormatting.place(null, null),
        ParkingFormatting.stopLine(STOP, WARSAW),
        ParkingFormatting.placeLine(PLACE, WARSAW),
        ParkingFormatting.duration(Duration.ofHours(51)),
    )

    @Test
    fun `no statistic or parking text carries a secret`() {
        for (text in onScreen()) {
            val forbidden =
                listOf("token", "Token", "VIN", "p@ss", "secret", "/data/", ".db", "Exception")
            for (word in forbidden) {
                assertFalse(word in text, "$word in: $text")
            }
        }
    }

    @Test
    fun `no number on the two screens is finer than the archive shows`() {
        // More decimals than the archive has would mean a raw GPS position on the screen.
        val tooFine = Regex("\\d[.,]\\d{4,}")
        for (text in onScreen()) {
            assertFalse(tooFine.containsMatchIn(text), "over-precise number in: $text")
        }
    }

    @Test
    fun `a place shows its position only in the coarse coordinate line`() {
        assertEquals("54.372, 18.638", ParkingFormatting.place(54.37249183746291, 18.63810294736281))
        val counts = ParkingFormatting.placeLine(PLACE, WARSAW)
        // The counts line carries no position at all, so only one line can ever leak one.
        val leaksPosition = Regex("\\d\\.\\d{3,}").containsMatchIn(counts)
        assertFalse(leaksPosition, "a position in the counts line: $counts")
        assertTrue("54.3724918" !in counts, counts)
    }

    @Test
    fun `a sentence starts with a capital and a value fragment does not`() {
        val sentences = listOf(
            Texts.STATS_TITLE,
            Texts.STATS_EMPTY,
            Texts.STATS_READ_ERROR,
            Texts.STATS_EV_SECTION,
            Texts.STATS_RANKING,
            Texts.STATS_YEAR_OVER_YEAR,
            Texts.STATS_YEARS,
            Texts.STATS_SEASONS,
            Texts.statsLeftOut(4, 2),
            Texts.PARKING_TITLE,
            Texts.PARKING_EMPTY,
            Texts.PARKING_READ_ERROR,
            Texts.PARKING_PRIVACY,
            Texts.PARKING_PLACES,
            Texts.PARKING_RECENT,
            Texts.parkingSkipped(3, 1),
        )
        for (text in sentences) {
            assertTrue(text.isNotBlank(), "a blank text never reaches the screen")
            assertTrue(text.first().isUpperCase(), text)
        }
        val fragments = listOf(Texts.STATS_NO_EV_DATA, Texts.PARKING_NO_POSITION, Texts.SEASON_WINTER)
        for (fragment in fragments) {
            assertTrue(fragment.first().isLowerCase(), fragment)
        }
    }
}
