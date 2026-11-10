package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.PlanPopulation
import pl.lk.toyotacompanion.core.domain.PlannedRange
import pl.lk.toyotacompanion.core.domain.PlannedTripResult
import pl.lk.toyotacompanion.core.domain.PlanSample
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TrafficClass
import pl.lk.toyotacompanion.core.domain.TripCost

private fun range(low: String = "0.5", high: String = "0.7", cost: String? = "3", costHigh: String? = "4", population: PlanPopulation = PlanPopulation.SIMILAR_LENGTH) =
    PlannedRange(
        band = TrafficClass.FLOWING,
        population = population,
        trips = 37,
        kindTrips = 212,
        litresLow = BigDecimal(low),
        litresHigh = BigDecimal(high),
        costLow = cost?.let(::BigDecimal),
        costHigh = costHigh?.let(::BigDecimal),
    )

/** The calculator of the statistics (PRD F52, docs/SPEC.md §52): the text, the km as typed, and the samples and price from the archive. */
class PlannedTripFormattingTest {
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

    @Test
    fun `the range reads as litres and zloty with an en dash`() {
        assertEquals("0,5–0,7 l", PlannedTripFormatting.litres(range()))
        assertEquals("3–4 zł", PlannedTripFormatting.money(range()))
        assertEquals("ok. 0,5–0,7 l · 3–4 zł", PlannedTripFormatting.headline(range()))
    }

    @Test
    fun `one value stays one value and a missing price leaves only litres`() {
        assertEquals("0,6 l", PlannedTripFormatting.litres(range(low = "0.6", high = "0.6")))
        assertNull(PlannedTripFormatting.money(range(cost = null, costHigh = null)))
        assertEquals("ok. 0,5–0,7 l", PlannedTripFormatting.headline(range(cost = null, costHigh = null)))
    }

    @Test
    fun `the helper line says which trips the range came from`() {
        assertEquals("z 37 podobnych tras", PlannedTripFormatting.helper(range()))
        assertEquals(
            "z 212 tras tego rodzaju (za mało podobnej długości)",
            PlannedTripFormatting.helper(range(population = PlanPopulation.WHOLE_KIND)),
        )
    }

    @Test
    fun `a result that is no range gives one line`() {
        assertEquals(Texts.PLANNED_BAD_KM, PlannedTripFormatting.notRange(PlannedTripResult.BadKm))
        assertEquals("za mało tras tego rodzaju: 4 z 10", PlannedTripFormatting.notRange(PlannedTripResult.TooFew(4)))
        assertNull(PlannedTripFormatting.notRange(PlannedTripResult.Range(range())))
    }

    @Test
    fun `the km are read with a comma or a dot and a wrong or empty value gives none`() {
        assertEquals(BigDecimal("12.5"), PlannedTripFormatting.parseKm("12,5"))
        assertEquals(BigDecimal("12.5"), PlannedTripFormatting.parseKm("12.5"))
        assertNull(PlannedTripFormatting.parseKm(""))
        assertNull(PlannedTripFormatting.parseKm("abc"))
        assertNull(PlannedTripFormatting.parseKm("0"))
    }

    @Test
    fun `the calculator texts differ between the languages and carry no vin`() {
        fun all() = listOf(
            Texts.PLANNED_TITLE, Texts.PLANNED_HINT, Texts.PLANNED_KM_LABEL, Texts.PLANNED_KIND_LABEL, Texts.PLANNED_BAD_KM,
            Texts.PLANNED_NO_PRICE, Texts.plannedKind(TrafficClass.CITY), Texts.plannedResult("0,5 l", "3 zł"),
            Texts.plannedSimilar(10), Texts.plannedWholeKind(12), Texts.plannedTooFew(4, 10),
        )
        val polish = all()
        Lang.current = AppLanguage.EN
        val english = all()
        for ((pl, en) in polish.zip(english)) {
            assertFalse("VIN" in pl + en, pl)
            assertFalse(pl == en, "untranslated: $pl")
        }
        assertEquals("approx. 0,5 l · 3 zł", english[7])
    }

    // ---- samples and price from the archive ----

    private val zone = ZoneOffset.UTC

    private fun stored(
        id: String,
        start: String,
        km: Double,
        fuel: String,
        speed: Double? = 30.0,
        price: String? = null,
        source: CostSource = CostSource.REFUEL,
    ): StoredTrip {
        val begin = Instant.parse(start)
        val record = TripRecord(Trip(id, begin, begin.plusSeconds(600), km, BigDecimal(fuel), null), "VINAAA111", averageSpeed = speed)
        val cost = price?.let { TripCost(id, BigDecimal("3.00"), BigDecimal(it), "r", source) }
        return StoredTrip(record, cost)
    }

    @Test
    fun `the samples keep the distance, the litres and the kind and drop the trips without a usable speed`() {
        val samples = planSamples(
            listOf(
                stored("a", "2026-10-01T08:00:00Z", 12.0, "0.9", speed = 30.0),
                stored("b", "2026-10-02T08:00:00Z", 1.0, "0.1", speed = 30.0),
                stored("c", "2026-10-03T08:00:00Z", 12.0, "0.9", speed = null),
                stored("d", "2026-10-04T08:00:00Z", 60.0, "3.1", speed = 90.0),
            ),
        )
        assertEquals(
            listOf(
                PlanSample(BigDecimal("12.0"), BigDecimal("0.9"), TrafficClass.FLOWING),
                PlanSample(BigDecimal("60.0"), BigDecimal("3.1"), TrafficClass.HIGHWAY),
            ),
            samples,
        )
    }

    @Test
    fun `the price is the newest priced trip, then the default price, and nothing without either`() {
        val priced = listOf(
            stored("old", "2026-10-01T08:00:00Z", 12.0, "0.9", price = "6.10"),
            stored("new", "2026-10-05T08:00:00Z", 12.0, "0.9", price = "6.40"),
            stored("unpriced", "2026-10-09T08:00:00Z", 12.0, "0.9", price = null),
        )
        assertEquals(BigDecimal("6.40"), lastPlanPrice(priced))
        assertEquals(BigDecimal("6.40"), computeStats(priced, zone, BigDecimal("6.00")).planPrice)
        val none = listOf(stored("x", "2026-10-01T08:00:00Z", 12.0, "0.9"))
        assertNull(lastPlanPrice(none))
        assertEquals(BigDecimal("6.00"), computeStats(none, zone, BigDecimal("6.00")).planPrice)
        assertNull(computeStats(none, zone).planPrice)
    }

    @Test
    fun `a price from a trip without an amount or with the source none is not a price`() {
        val none = listOf(stored("n", "2026-10-01T08:00:00Z", 12.0, "0.9", price = "6.10", source = CostSource.NONE))
        assertNull(lastPlanPrice(none))
    }

    @Test
    fun `the calculator starts with the kind that has the most trips`() {
        val trips = listOf(
            stored("a", "2026-10-01T08:00:00Z", 12.0, "0.9", speed = 10.0),
            stored("b", "2026-10-02T08:00:00Z", 12.0, "0.9", speed = 30.0),
            stored("c", "2026-10-03T08:00:00Z", 12.0, "0.9", speed = 31.0),
        )
        assertEquals(TrafficClass.FLOWING, computeStats(trips, zone).planDefaultBand)
        assertEquals(null, computeStats(emptyList(), zone).planDefaultBand)
    }
}
