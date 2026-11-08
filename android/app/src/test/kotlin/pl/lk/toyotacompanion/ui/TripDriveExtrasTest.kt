package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.data.TripRecord
import pl.lk.toyotacompanion.core.domain.Trip

private fun record(km: Double, overspeedM: Long? = null, highwayM: Long? = null) = TripRecord(
    trip = Trip("t", Instant.parse("2026-10-05T10:00:00Z"), Instant.parse("2026-10-05T11:00:00Z"), km, BigDecimal("5.000"), null),
    vin = "VINAAA111",
    overspeedLengthM = overspeedM,
    highwayLengthM = highwayM,
)

/** docs/SPEC.md §41: the extra line of a trip row with the motorway and speeding kilometres. */
class TripDriveExtrasTest {
    @Test
    fun `both lengths are shown in kilometres, the speeding one with its share of the trip`() {
        assertEquals(
            "autostrada 4,2 km · przekroczenia 1,3 km (9 %)",
            tripDriveExtrasLine(record(km = 15.2, overspeedM = 1_334, highwayM = 4_210)),
        )
    }

    @Test
    fun `one length alone gives one part`() {
        assertEquals("autostrada 12,0 km", tripDriveExtrasLine(record(km = 30.0, highwayM = 12_000)))
        assertEquals("przekroczenia 0,5 km (2 %)", tripDriveExtrasLine(record(km = 25.0, overspeedM = 500)))
    }

    @Test
    fun `nothing reported or zero gives no line`() {
        assertNull(tripDriveExtrasLine(record(km = 10.0)))
        assertNull(tripDriveExtrasLine(record(km = 10.0, overspeedM = 0, highwayM = 0)))
    }

    @Test
    fun `the share is capped at 100 and a broken distance is not divided by`() {
        assertEquals("przekroczenia 20,0 km (100 %)", tripDriveExtrasLine(record(km = 10.0, overspeedM = 20_000)))
        assertEquals("przekroczenia 1,0 km (0 %)", tripDriveExtrasLine(record(km = 0.0, overspeedM = 1_000)))
        assertEquals("przekroczenia 1,0 km (0 %)", tripDriveExtrasLine(record(km = Double.NaN, overspeedM = 1_000)))
    }

    @Test
    fun `the line speaks English too`() {
        Lang.current = AppLanguage.EN
        try {
            assertEquals(
                "motorway 4,2 km · speeding 1,3 km (9 %)",
                tripDriveExtrasLine(record(km = 15.2, overspeedM = 1_334, highwayM = 4_210)),
            )
        } finally {
            Lang.current = AppLanguage.PL
        }
    }
}
