package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import pl.lk.toyotacompanion.core.data.StoredTrip
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.ComponentState
import pl.lk.toyotacompanion.core.domain.CostSource
import pl.lk.toyotacompanion.core.domain.DoorPosition
import pl.lk.toyotacompanion.core.domain.ServiceRecord
import pl.lk.toyotacompanion.core.domain.Trip
import pl.lk.toyotacompanion.core.domain.TripCost
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus
import pl.lk.toyotacompanion.core.domain.WindowPosition

/** The second round of C32 (U8, U9, U12, U16, U17): location, service rows, price lines, vehicle summary. */
class ScreenDetailsTest {
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

    // ---- U8: location ----

    @Test
    fun `Toyota's Last Parked reads in Polish and any other name stays as sent`() {
        assertEquals("Ostatnie parkowanie", locationNameText("Last Parked"))
        assertEquals("Ostatnie parkowanie", locationNameText("  last parked "))
        assertEquals("ul. Długa 1, Gdańsk", locationNameText("ul. Długa 1, Gdańsk"))
        assertEquals("Current", locationNameText("Current"))
        assertNull(locationNameText(null))
        assertNull(locationNameText("  "))
        Lang.current = AppLanguage.EN
        assertEquals("Last parked", locationNameText("Last Parked"))
    }

    @Test
    fun `one time line, the update only when it differs by an hour or more`() {
        val at = Instant.parse("2026-11-08T10:00:00Z")
        val oneMinute = VehicleLocation(acquiredAt = at, lastUpdate = at.plusSeconds(60))
        assertEquals(listOf("Pozycja z 08.11 10:00"), locationTimeLines(oneMinute, ZoneOffset.UTC))
        val justUnderHour = VehicleLocation(acquiredAt = at, lastUpdate = at.plusSeconds(3_599))
        assertEquals(1, locationTimeLines(justUnderHour, ZoneOffset.UTC).size)
        val hour = VehicleLocation(acquiredAt = at, lastUpdate = at.plusSeconds(3_600))
        assertEquals(listOf("Pozycja z 08.11 10:00", "Ostatnia aktualizacja 08.11 11:00"), locationTimeLines(hour, ZoneOffset.UTC))
        // The update older than the position differs the same way.
        val earlier = VehicleLocation(acquiredAt = at, lastUpdate = at.minusSeconds(7_200))
        assertEquals(2, locationTimeLines(earlier, ZoneOffset.UTC).size)
    }

    @Test
    fun `without a position time the update is the one line`() {
        val at = Instant.parse("2026-11-08T10:00:00Z")
        assertEquals(listOf("Ostatnia aktualizacja 08.11 10:00"), locationTimeLines(VehicleLocation(lastUpdate = at), ZoneOffset.UTC))
        assertEquals(listOf("Pozycja z 08.11 10:00"), locationTimeLines(VehicleLocation(acquiredAt = at), ZoneOffset.UTC))
        assertEquals(emptyList(), locationTimeLines(VehicleLocation(), ZoneOffset.UTC))
    }

    private val home = ParkingPlace(54.3720, 18.6380, 40, java.time.Duration.ofDays(30), Instant.parse("2026-11-01T10:00:00Z"))
    private val work = ParkingPlace(54.4000, 18.6000, 20, java.time.Duration.ofDays(10), Instant.parse("2026-11-01T10:00:00Z"))

    @Test
    fun `a position within 150 m of home or work gets its name`() {
        val known = KnownPlaces(home, work)
        assertEquals("Dom", knownPlaceNear(54.3720, 18.6380, known))
        // 0.001 deg of latitude is about 111 m, still inside the 150 m of the diary.
        assertEquals("Dom", knownPlaceNear(54.3730, 18.6380, known))
        assertEquals("Praca", knownPlaceNear(54.4000, 18.6005, known))
        // 0.002 deg is about 222 m: another place.
        assertNull(knownPlaceNear(54.3740, 18.6380, known))
    }

    @Test
    fun `no coordinates, no places or a broken value name nothing`() {
        assertNull(knownPlaceNear(null, 18.6, KnownPlaces(home, work)))
        assertNull(knownPlaceNear(54.3, null, KnownPlaces(home, work)))
        assertNull(knownPlaceNear(Double.NaN, 18.6, KnownPlaces(home, work)))
        assertNull(knownPlaceNear(54.3720, 18.6380, null))
        assertNull(knownPlaceNear(54.3720, 18.6380, KnownPlaces(null, null)))
        assertEquals("Praca", knownPlaceNear(54.4000, 18.6000, KnownPlaces(null, work)))
    }

    @Test
    fun `where home and work are equally near, home wins`() {
        val same = home.copy()
        assertEquals("Dom", knownPlaceNear(home.lat, home.lon, KnownPlaces(home, same)))
    }

    // ---- U9: service rows ----

    @Test
    fun `a service record is label and value rows, the category has the confirmed meaning and code`() {
        val row = serviceRow(
            ServiceRecord(mileage = 15320, mileageUnit = "km", category = "MNT", dealer = "Dealer Gdynia", operations = listOf("Wymiana oleju", "Filtr")),
        )
        assertEquals(
            listOf(
                LabelValue("Przebieg", "15\u00A0320 km"),
                LabelValue("Kategoria", "Przegląd okresowy (MNT)"),
                LabelValue("Dealer", "Dealer Gdynia"),
                LabelValue("Czynności", "Wymiana oleju\nFiltr"),
            ),
            serviceDetails(row),
        )
    }

    @Test
    fun `missing parts are left out or said so, and an unknown code stays as sent`() {
        val rows = serviceDetails(serviceRow(ServiceRecord(category = "XYZ", operations = null)))
        assertEquals(
            listOf(
                LabelValue("Kategoria", "XYZ"),
                LabelValue("Dealer", "brak danych"),
                LabelValue("Czynności", "brak opisu"),
            ),
            rows,
        )
        Lang.current = AppLanguage.EN
        try {
            assertEquals("Mileage", Texts.SERVICE_MILEAGE_LABEL)
            assertEquals("Category", Texts.SERVICE_CATEGORY_LABEL)
        } finally {
            Lang.current = AppLanguage.PL
        }
    }

    // ---- U12: price per litre where it changes ----

    private fun trip(id: String, start: String, price: String?, source: CostSource = CostSource.REFUEL): StoredTrip {
        val begin = Instant.parse(start)
        val record = TripRecord(Trip(id, begin, begin.plusSeconds(600), 10.0, BigDecimal("0.5"), null), "VINAAA111")
        val cost = price?.let { TripCost(id, BigDecimal("3.15"), BigDecimal(it), "r", source) }
        return StoredTrip(record, cost)
    }

    private fun shown(vararg trips: StoredTrip): Set<String> = tripIdsWithPriceLine(groupTripsByMonth(trips.toList(), ZoneOffset.UTC))

    @Test
    fun `the price shows on the newest trip of every run of equal prices`() {
        // Newest first, as the list reads them: 6.50 6.50 | 6.30 6.30 6.30.
        val ids = shown(
            trip("e", "2026-10-05T10:00:00Z", "6.50"),
            trip("d", "2026-10-04T10:00:00Z", "6.50"),
            trip("c", "2026-10-03T10:00:00Z", "6.30"),
            trip("b", "2026-10-02T10:00:00Z", "6.30"),
            trip("a", "2026-10-01T10:00:00Z", "6.30"),
        )
        // e is the newest of all (and of the month); d equals its next (e) and is not shown; c is the newest 6.30 (next d differs).
        assertEquals(setOf("e", "c"), ids)
    }

    @Test
    fun `the newest trip of every month shows its price even when it did not change`() {
        val ids = shown(
            trip("d", "2026-11-03T10:00:00Z", "6.30"),
            trip("c", "2026-11-01T10:00:00Z", "6.30"),
            trip("b", "2026-10-31T22:00:00Z", "6.30"),
            trip("a", "2026-10-20T10:00:00Z", "6.30"),
        )
        // The grouping is in UTC here: d is the newest of November, b the newest of October; c and a equal their next trips.
        assertEquals(setOf("d", "b"), ids)
    }

    @Test
    fun `a change shows against the chronologically next trip across months`() {
        val ids = shown(
            trip("c", "2026-11-02T10:00:00Z", "6.60"),
            trip("b", "2026-11-01T10:00:00Z", "6.30"),
            trip("a", "2026-10-31T10:00:00Z", "6.30"),
        )
        // c is the newest; b differs from c; a equals b but is the newest trip of October.
        assertEquals(setOf("c", "b", "a"), ids)
    }

    @Test
    fun `the top of the list always has a price and the older rows of a run stay quiet`() {
        val ids = shown(
            trip("c", "2026-10-03T10:00:00Z", "6.30"),
            trip("b", "2026-10-02T10:00:00Z", "6.30"),
            trip("a", "2026-10-01T10:00:00Z", "6.30"),
        )
        assertEquals(setOf("c"), ids)
    }

    @Test
    fun `a trip without a price shows no line and the default price note counts as part of the line`() {
        val ids = shown(
            trip("d", "2026-10-04T10:00:00Z", "6.30", CostSource.DEFAULT),
            trip("c", "2026-10-03T10:00:00Z", null),
            trip("b", "2026-10-02T10:00:00Z", "6.30"),
            trip("a", "2026-10-01T10:00:00Z", "6.30"),
        )
        // d is the newest; c has no line; b differs from its next (c has none) and shows; a equals b and stays quiet.
        assertEquals(setOf("d", "b"), ids)
        assertEquals(emptySet(), shown())
    }

    // ---- U17: vehicle summary ----

    private fun status(door: ComponentState? = null, window: ComponentState? = null, hood: ComponentState? = null) = VehicleStatus(
        doors = listOfNotNull(
            door?.let { DoorPosition.DRIVER to it },
            hood?.let { DoorPosition.HOOD to it },
        ).toMap(),
        windows = listOfNotNull(window?.let { WindowPosition.DRIVER to it }).toMap(),
    )

    private val shut = ComponentState(isOpen = false, isLocked = true)

    @Test
    fun `everything shut and locked is one sentence`() {
        val s = status(door = shut, window = ComponentState(isOpen = false), hood = ComponentState(isOpen = false))
        assertEquals(VehicleSummary.AllClosed, vehicleSummary(s))
        assertEquals("Wszystko zamknięte i zablokowane", vehicleSummaryText(s))
        Lang.current = AppLanguage.EN
        assertEquals("Everything is closed and locked", vehicleSummaryText(s))
    }

    @Test
    fun `open or unlocked parts are counted and marked`() {
        val s = status(door = ComponentState(isOpen = false, isLocked = false), window = ComponentState(isOpen = true), hood = ComponentState(isOpen = false))
        assertEquals(VehicleSummary.Deviations(2), vehicleSummary(s))
        assertEquals("Do sprawdzenia: 2 – otwarte lub odblokowane", vehicleSummaryText(s))
        assertEquals(listOf(true), doorLines(s).filter { it.deviation }.map { it.deviation })
        assertTrue(windowLines(s).single().deviation)
        assertFalse(doorLines(s).first { it.name == "Maska" }.deviation)
        assertFalse(isDeviation(shut))
        Lang.current = AppLanguage.EN
        assertEquals("To check: 2 – open or unlocked", vehicleSummaryText(s))
    }

    @Test
    fun `a part without data is not called closed and an empty payload has no sentence`() {
        assertNull(vehicleSummary(status(door = shut, window = ComponentState())))
        assertNull(vehicleSummaryText(VehicleStatus()))
        // A deviation still counts when another part has no data.
        assertEquals(VehicleSummary.Deviations(1), vehicleSummary(status(door = ComponentState(isOpen = true), window = ComponentState())))
    }

    // ---- U16/U11: texts with a counted noun ----

    @Test
    fun `a short count of trips is declined in Polish and English`() {
        assertEquals("1 trasa", Texts.tripsShort(1))
        assertEquals("2 trasy", Texts.tripsShort(2))
        assertEquals("5 tras", Texts.tripsShort(5))
        assertEquals("12 tras", Texts.tripsShort(12))
        assertEquals("22 trasy", Texts.tripsShort(22))
        assertEquals("1\u00A0024 trasy", Texts.tripsShort(1024))
        Lang.current = AppLanguage.EN
        assertEquals("1 trip", Texts.tripsShort(1))
        assertEquals("7 trips", Texts.tripsShort(7))
    }

    @Test
    fun `a week row reads from the date, km and trips`() {
        assertEquals("od 09.11 · 36,2 z 62,5 km · 7 tras", Texts.evWeekLine("09.11", "36,2", "62,5", 7))
        assertEquals("od 09.11 · 1,0 z 2,0 km · 1 trasa", Texts.evWeekLine("09.11", "1,0", "2,0", 1))
        Lang.current = AppLanguage.EN
        assertEquals("from 09.11 · 36,2 of 62,5 km · 7 trips", Texts.evWeekLine("09.11", "36,2", "62,5", 7))
    }

    @Test
    fun `the health hint no longer names the backend`() {
        for (language in AppLanguage.entries) {
            Lang.current = language
            assertFalse(Texts.HEALTH_HINT.contains("backend", ignoreCase = true), Texts.HEALTH_HINT)
        }
    }
}
