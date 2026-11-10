package pl.lk.toyotacompanion.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.ServiceRecord
import pl.lk.toyotacompanion.core.domain.ToyotaNotification

/** docs/SPEC.md §45-§46: the service history and notification rows, without a guessed value. */
class ServiceFormattingTest {

    private val zone = ZoneOffset.UTC

    @Test
    fun `a service date is day dot month dot year`() {
        assertEquals("12.03.2026", serviceDateText(LocalDate.parse("2026-03-12")))
        assertEquals("04.11.2025", serviceDateText(LocalDate.parse("2025-11-04")))
        assertEquals(Texts.SERVICE_NO_DATE, serviceDateText(null))
    }

    @Test
    fun `the dealer line has one label and falls back to no data`() {
        assertEquals("Toyota Gdynia", serviceDealerText(ServiceRecord(dealer = "Toyota Gdynia")))
        assertEquals(Texts.COMPONENT_UNKNOWN, serviceDealerText(ServiceRecord(dealer = null)))
        assertEquals("Dealer", Texts.SERVICE_DEALER)
    }

    @Test
    fun `a service row shows the mileage with Toyota's unit and keeps the untyped parts honest`() {
        val row = serviceRow(
            ServiceRecord(
                date = LocalDate.parse("2026-03-12"),
                mileage = 15320,
                mileageUnit = "km",
                category = "Przegląd",
                operations = listOf("Olej", "Filtr"),
                dealer = "Toyota Gdynia",
            ),
        )

        assertEquals("12.03.2026", row.title)
        assertEquals("15\u00A0320 km", row.mileage)
        assertEquals("Przegląd", row.category)
        assertEquals("Toyota Gdynia", row.dealer)
        assertEquals(listOf("Olej", "Filtr"), row.operations)
        // A record Toyota sent without a unit shows the number alone; nothing is assumed.
        assertEquals("15\u00A0320", serviceRow(ServiceRecord(mileage = 15320)).mileage)
        assertNull(serviceRow(ServiceRecord()).mileage)
        assertNull(serviceRow(ServiceRecord(operations = null)).operations)
    }

    @Test
    fun `the owner-confirmed category codes get a description, unknown codes stay as sent`() {
        assertEquals("Przegląd okresowy (MNT)", serviceCategoryText("MNT"))
        assertEquals("Wydanie auta (VDE)", serviceCategoryText("VDE"))
        assertEquals("XYZ", serviceCategoryText("XYZ"))
        assertEquals("Kategoria", Texts.SERVICE_CATEGORY_LABEL)

        Lang.current = AppLanguage.EN
        try {
            assertEquals("Scheduled maintenance (MNT)", serviceCategoryText("MNT"))
            assertEquals("Vehicle delivery (VDE)", serviceCategoryText("VDE"))
            assertEquals("XYZ", serviceCategoryText("XYZ"))
            assertEquals("Category", Texts.SERVICE_CATEGORY_LABEL)
        } finally {
            Lang.current = AppLanguage.PL
        }
    }

    @Test
    fun `the service row uses the confirmed category description`() {
        val details = serviceDetails(serviceRow(ServiceRecord(category = "MNT")))
        assertEquals(LabelValue("Kategoria", "Przegląd okresowy (MNT)"), details[0])
    }

    @Test
    fun `a notification shows the date in the local zone and its read state`() {
        assertEquals("01.11 10:00", notificationDateText(Instant.parse("2026-11-01T10:00:00Z"), zone))
        assertEquals(Texts.NOTIFICATIONS_NO_DATE, notificationDateText(null, zone))
        assertEquals(Texts.NOTIFICATIONS_READ, notificationReadText(true))
        assertEquals(Texts.NOTIFICATIONS_UNREAD, notificationReadText(false))
        assertNull(notificationReadText(null))
    }

    @Test
    fun `the kind line joins what Toyota sent and skips what it did not`() {
        assertEquals(
            "Service · INFO",
            notificationKindText(ToyotaNotification(category = "Service", type = "INFO")),
        )
        assertEquals("Service", notificationKindText(ToyotaNotification(category = "Service")))
        assertEquals("INFO", notificationKindText(ToyotaNotification(type = "INFO")))
        assertNull(notificationKindText(ToyotaNotification()))
    }
}
