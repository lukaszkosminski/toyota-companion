package pl.lk.toyotacompanion.ui

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.ComponentState
import pl.lk.toyotacompanion.core.domain.DoorPosition
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus
import pl.lk.toyotacompanion.core.domain.WindowPosition

/** docs/SPEC.md §42-§43: the rows of the vehicle status screen, only what Toyota reported. */
class VehicleDataFormattingTest {

    private val seen: Instant = Instant.parse("2026-11-08T10:00:00Z")

    @Test
    fun `a component says only what Toyota reported`() {
        assertEquals("zamknięte · zablokowane", componentStateText(ComponentState(isOpen = false, isLocked = true)))
        assertEquals("otwarte · odblokowane", componentStateText(ComponentState(isOpen = true, isLocked = false)))
        assertEquals("otwarte", componentStateText(ComponentState(isOpen = true)))
        assertEquals("zablokowane", componentStateText(ComponentState(isLocked = true)))
        assertEquals("brak danych", componentStateText(ComponentState(observedAt = seen)))
    }

    @Test
    fun `doors come in a fixed order and missing ones are left out`() {
        val status = VehicleStatus(
            doors = mapOf(
                DoorPosition.HOOD to ComponentState(isOpen = false),
                DoorPosition.REAR_BACK to ComponentState(isOpen = true, isLocked = false),
                DoorPosition.DRIVER to ComponentState(isOpen = false, isLocked = true),
            ),
        )

        assertEquals(
            listOf(
                ComponentLine("Kierowca", "zamknięte · zablokowane"),
                ComponentLine("Bagażnik", "otwarte · odblokowane", deviation = true),
                ComponentLine("Maska", "zamknięte"),
            ),
            doorLines(status),
        )
    }

    @Test
    fun `windows come in a fixed order and an empty payload gives no rows`() {
        val status = VehicleStatus(
            windows = mapOf(
                WindowPosition.REAR_RIGHT to ComponentState(isOpen = true),
                WindowPosition.PASSENGER to ComponentState(isOpen = false),
            ),
        )

        assertEquals(listOf(ComponentLine("Pasażer", "zamknięte"), ComponentLine("Tył prawy", "otwarte", deviation = true)), windowLines(status))
        assertEquals(emptyList(), doorLines(VehicleStatus()))
        assertEquals(emptyList(), windowLines(VehicleStatus()))
    }

    @Test
    fun `english names and states are translated`() {
        Lang.current = AppLanguage.EN
        try {
            val line = doorLines(VehicleStatus(doors = mapOf(DoorPosition.REAR_LEFT to ComponentState(isOpen = false, isLocked = false))))
            assertEquals(listOf(ComponentLine("Rear left", "closed · unlocked", deviation = true)), line)
            assertEquals("no data", componentStateText(ComponentState()))
        } finally {
            Lang.current = AppLanguage.PL
        }
    }

    @Test
    fun `coordinates use a dot and five places from the decimal value`() {
        assertEquals("54.36850, 18.61420", coordinatesText(VehicleLocation(latitude = 54.3685, longitude = 18.6142)))
        // A binary BigDecimal of 54.123455 is just below the half; the decimal text rounds it up.
        assertEquals("54.12346, -0.12346", coordinatesText(VehicleLocation(latitude = 54.123455, longitude = -0.123455)))
        assertEquals("0.00000, 0.00000", coordinatesText(VehicleLocation(latitude = 0.0, longitude = 0.0)))
    }

    @Test
    fun `coordinates that are not finite are not shown`() {
        assertNull(coordinatesText(VehicleLocation(latitude = Double.NaN, longitude = 18.6)))
        assertNull(coordinatesText(VehicleLocation(latitude = 54.3, longitude = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun `the warning count is said once`() {
        assertEquals("Aktywne ostrzeżenia: 3", Texts.vehicleWarnings(3))
        Lang.current = AppLanguage.EN
        try {
            assertEquals("Active warnings: 0", Texts.vehicleWarnings(0))
        } finally {
            Lang.current = AppLanguage.PL
        }
    }

    @Test
    fun `coordinates need both values`() {
        assertNull(coordinatesText(VehicleLocation(latitude = 54.37)))
        assertNull(coordinatesText(VehicleLocation(longitude = 18.61)))
        assertNull(coordinatesText(VehicleLocation()))
    }
}
