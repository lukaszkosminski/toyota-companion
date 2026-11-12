package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Stations at refuelling (PRD F57, docs/SPEC.md §57); the same case names live in `backend/tests/test_domain_stations.py`. */
class StationOpsTest {
    private val day0: Instant = Instant.parse("2026-10-01T08:00:00Z")

    private fun at(days: Double): Instant = day0.plusSeconds((days * 86_400).toLong())

    private fun d(value: String) = BigDecimal(value)

    private fun refuel(
        id: String,
        day: Double,
        station: String? = null,
        liters: String? = "30.000",
        total: String? = "186.00",
        status: RefuelStatus = RefuelStatus.CONFIRMED,
    ) = Refuel(
        id = id,
        ts = at(day),
        odometerKm = 1000.0 + day,
        levelBefore = 20,
        levelAfter = 90,
        liters = liters?.let(::d),
        pricePerLiter = if (status == RefuelStatus.CONFIRMED) d("6.190") else null,
        totalCost = total?.let(::d),
        status = status,
        filledToFull = if (liters != null && status == RefuelStatus.CONFIRMED) true else null,
        station = station,
    )

    private fun interval(startId: String, liters: String, distance: String, status: EconomyStatus = EconomyStatus.OK) = EconomyInterval(
        startRefuelId = startId,
        endRefuelId = "next",
        status = status,
        trips = 1,
        distanceKm = d(distance),
        evDistanceKm = d("0"),
        computerLiters = d("0"),
        actualLiters = d(liters),
        odometerKm = null,
        computerLPer100Km = null,
        actualLPer100Km = null,
        differencePct = null,
    )

    @Test
    fun trims_and_collapses_whitespace() {
        assertEquals("Auchan Szczęśliwa", normalizeStation("  Auchan   Szczęśliwa \n"))
    }

    @Test
    fun blank_becomes_null() {
        assertNull(normalizeStation(null))
        assertNull(normalizeStation(""))
        assertNull(normalizeStation("   "))
    }

    @Test
    fun long_name_is_cut_to_the_limit() {
        assertEquals(60, STATION_MAX_LENGTH)
        assertEquals("x".repeat(60), normalizeStation("x".repeat(61)))
    }

    @Test
    fun a_cut_name_does_not_keep_trailing_space() {
        assertEquals("a".repeat(59), normalizeStation("a".repeat(59) + " b"))
    }

    @Test
    fun a_cut_name_does_not_split_surrogate_pairs() {
        assertEquals("a".repeat(59), normalizeStation("a".repeat(59) + "\uD83D\uDE97"))
        assertEquals("a".repeat(58) + "\uD83D\uDE97", normalizeStation("a".repeat(58) + "\uD83D\uDE97"))
    }

    @Test
    fun case_differences_are_one_station_with_the_newest_spelling() {
        val summaries = stationSummaries(listOf(refuel("a", 0.0, "Auchan"), refuel("b", 1.0, "auchan")), emptyList())
        assertEquals(listOf("auchan"), summaries.map { it.name })
        assertEquals(2, summaries[0].refuels)
    }

    @Test
    fun pending_and_unassigned_refuels_are_not_rows() {
        val summaries = stationSummaries(
            listOf(
                refuel("a", 0.0, "Auchan"),
                refuel("p", 1.0, "WATIS", status = RefuelStatus.PENDING),
                refuel("n", 2.0, null),
                refuel("b", 3.0, "   "),
            ),
            emptyList(),
        )
        assertEquals(listOf("Auchan"), summaries.map { it.name })
    }

    @Test
    fun average_price_is_weighted_by_liters() {
        val summaries = stationSummaries(
            listOf(
                refuel("a", 0.0, "Auchan", liters = "10.000", total = "60.00"),
                refuel("b", 1.0, "Auchan", liters = "30.000", total = "210.00"),
            ),
            emptyList(),
        )
        assertEquals(2, summaries[0].pricedRefuels)
        assertEquals(d("6.750"), summaries[0].avgPricePerLiter)
    }

    @Test
    fun average_price_starts_from_one_refuel() {
        assertEquals(d("6.200"), stationSummaries(listOf(refuel("a", 0.0, "WATIS")), emptyList())[0].avgPricePerLiter)
    }

    @Test
    fun a_refuel_without_both_fields_does_not_move_the_average() {
        val summaries = stationSummaries(
            listOf(
                refuel("a", 0.0, "WATIS", liters = "10.000", total = "60.00"),
                refuel("b", 1.0, "WATIS", liters = null, total = null),
            ),
            emptyList(),
        )
        assertEquals(2, summaries[0].refuels)
        assertEquals(1, summaries[0].pricedRefuels)
        assertEquals(d("6.000"), summaries[0].avgPricePerLiter)
    }

    @Test
    fun consumption_needs_three_ok_intervals() {
        val refuels = listOf(refuel("a", 0.0, "WATIS"), refuel("b", 1.0, "WATIS"))
        val two = listOf(interval("a", "5.000", "100.0"), interval("b", "5.000", "100.0"))
        assertNull(stationSummaries(refuels, two)[0].lPer100Km)
        assertEquals(3, STATION_MIN_INTERVALS)
        val three = two + interval("a", "5.000", "100.0")
        assertEquals(d("5.00"), stationSummaries(refuels, three)[0].lPer100Km)
    }

    @Test
    fun consumption_is_pooled_not_an_average_of_averages() {
        val refuels = listOf(refuel("a", 0.0, "WATIS"))
        val intervals = listOf(interval("a", "5.000", "100.0"), interval("a", "15.000", "300.0"), interval("a", "3.000", "100.0"))
        // 23 l over 500 km = 4.60, while the mean of 5.00, 5.00 and 3.00 would be 4.33.
        assertEquals(d("4.60"), stationSummaries(refuels, intervals)[0].lPer100Km)
    }

    @Test
    fun only_ok_intervals_count() {
        val refuels = listOf(refuel("a", 0.0, "WATIS"))
        val intervals = listOf(
            interval("a", "5.000", "100.0"),
            interval("a", "5.000", "100.0"),
            interval("a", "5.000", "100.0", status = EconomyStatus.TOO_SHORT),
        )
        val summary = stationSummaries(refuels, intervals)[0]
        assertEquals(2, summary.intervals)
        assertNull(summary.lPer100Km)
    }

    @Test
    fun an_interval_belongs_to_the_opening_station() {
        val refuels = listOf(
            refuel("a", 0.0, "Auchan"),
            refuel("b", 1.0, "WATIS"),
            refuel("c", 2.0, "WATIS"),
            refuel("d", 3.0, "WATIS"),
        )
        val intervals = listOf(interval("a", "5.000", "100.0"), interval("b", "5.000", "100.0"), interval("c", "5.000", "100.0"))
        val byName = stationSummaries(refuels, intervals).associateBy { it.name }
        assertEquals(1, byName.getValue("Auchan").intervals)
        assertEquals(2, byName.getValue("WATIS").intervals)
        assertNull(byName.getValue("WATIS").lPer100Km)
    }

    @Test
    fun rows_sort_by_refuel_count_then_name() {
        val summaries = stationSummaries(
            listOf(refuel("a", 0.0, "WATIS"), refuel("b", 1.0, "Auchan"), refuel("c", 2.0, "Auchan"), refuel("d", 3.0, "Moya")),
            emptyList(),
        )
        assertEquals(listOf("Auchan", "Moya", "WATIS"), summaries.map { it.name })
    }

    @Test
    fun most_used_first_then_the_most_recent() {
        val names = stationSuggestions(
            listOf(refuel("a", 0.0, "WATIS"), refuel("b", 1.0, "Auchan"), refuel("c", 2.0, "WATIS"), refuel("d", 3.0, "Moya")),
        )
        assertEquals(listOf("WATIS", "Moya", "Auchan"), names)
    }

    @Test
    fun pending_and_blank_are_skipped_in_suggestions() {
        val names = stationSuggestions(
            listOf(
                refuel("a", 0.0, "Auchan"),
                refuel("p", 1.0, "WATIS", status = RefuelStatus.PENDING),
                refuel("b", 2.0, "   "),
                refuel("c", 3.0, null),
            ),
        )
        assertEquals(listOf("Auchan"), names)
    }

    @Test
    fun limit_keeps_the_head() {
        val refuels = (0 until 8).map { refuel(it.toString(), it.toDouble(), "S$it") }
        assertEquals(listOf("S7", "S6", "S5"), stationSuggestions(refuels, limit = 3))
    }
}
