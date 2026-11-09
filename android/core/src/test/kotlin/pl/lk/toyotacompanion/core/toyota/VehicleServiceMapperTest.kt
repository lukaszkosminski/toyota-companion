package pl.lk.toyotacompanion.core.toyota

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import pl.lk.toyotacompanion.core.domain.ServiceRecord
import pl.lk.toyotacompanion.core.toyota.dto.NotificationHistoryResponse
import pl.lk.toyotacompanion.core.toyota.dto.ServiceHistoryResponse

/** docs/SPEC.md §45-§46: the service history and notifications map to the domain without guessing. */
class VehicleServiceMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun serviceHistory(payload: String) =
        VehicleDataMapper.toServiceHistory(json.decodeFromString<ServiceHistoryResponse>(payload).payload)

    private fun notifications(payload: String, vin: String? = "VIN1234567890") =
        VehicleDataMapper.toNotifications(json.decodeFromString<NotificationHistoryResponse>(payload).payload, vin)

    @Test
    fun `service records keep date, mileage, category, operations and the dealer`() {
        val payload = """
            {"payload":{"serviceHistories":[
              {"serviceDate":"2026-03-12","mileage":15320,"unit":"km","serviceCategory":"Przegląd",
               "operationsPerformed":["Olej","Filtr kabinowy"],"servicingDealer":"Toyota Gdynia",
               "serviceHistoryId":"S1","roNumber":"RO-7","notes":"notatka","customerCreatedRecord":false},
              {"serviceDate":"2025-11-04","mileage":10000,"unit":"km","serviceCategory":"Serwis",
               "operationsPerformed":"Wymiana opon","serviceProvider":"ASO Gdańsk"}
            ]}}
        """.trimIndent()
        val history = serviceHistory(payload)

        assertEquals(2, history?.records?.size)
        val newest = history?.records?.first()
        assertEquals(LocalDate.parse("2026-03-12"), newest?.date)
        assertEquals(15320, newest?.mileage)
        assertEquals("km", newest?.mileageUnit)
        assertEquals("Przegląd", newest?.category)
        assertEquals(listOf("Olej", "Filtr kabinowy"), newest?.operations)
        assertEquals("Toyota Gdynia", newest?.dealer)
        // Not shown anywhere (owner's decision 2026-11-09): no id, no RO number, no notes, no creator flag.
        assertEquals(
            ServiceRecord(
                date = LocalDate.parse("2026-03-12"),
                mileage = 15320,
                mileageUnit = "km",
                category = "Przegląd",
                operations = listOf("Olej", "Filtr kabinowy"),
                dealer = "Toyota Gdynia",
            ),
            newest,
        )
    }

    @Test
    fun `a missing dealer falls back to the service provider under the same label`() {
        val payload = """
            {"payload":{"serviceHistories":[{"serviceDate":"2025-11-04","serviceProvider":"ASO Gdańsk"}]}}
        """.trimIndent()

        assertEquals("ASO Gdańsk", serviceHistory(payload)?.records?.first()?.dealer)
    }

    @Test
    fun `a record without a dealer keeps it unknown`() {
        val payload = """
            {"payload":{"serviceHistories":[{"serviceDate":"2025-11-04","servicingDealer":null}]}}
        """.trimIndent()

        assertNull(serviceHistory(payload)?.records?.first()?.dealer)
    }

    @Test
    fun `untyped operations are text, an empty list or unknown, never a guess`() {
        val payload = """
            {"payload":{"serviceHistories":[
              {"operationsPerformed":"Olej"},
              {"operationsPerformed":[]},
              {"operationsPerformed":{"code":"X"}},
              {"operationsPerformed":[{"code":"X"}]},
              {"operationsPerformed":null}
            ]}}
        """.trimIndent()
        val records = serviceHistory(payload)?.records.orEmpty()

        assertEquals(listOf("Olej"), records[0].operations)
        assertEquals(emptyList(), records[1].operations)
        assertNull(records[2].operations)
        assertNull(records[3].operations)
        assertNull(records[4].operations)
    }

    @Test
    fun `records are newest first and a record without a date sorts last`() {
        val payload = """
            {"payload":{"serviceHistories":[
              {"serviceDate":"2025-01-02","mileage":1},
              {"mileage":2},
              {"serviceDate":"2026-03-12","mileage":3}
            ]}}
        """.trimIndent()
        val records = serviceHistory(payload)?.records.orEmpty()

        assertEquals(listOf(LocalDate.parse("2026-03-12"), LocalDate.parse("2025-01-02"), null), records.map { it.date })
    }

    @Test
    fun `no payload is no data and an empty history is an empty history`() {
        assertNull(serviceHistory("""{"payload":null}"""))
        assertEquals(emptyList(), serviceHistory("""{"payload":{"serviceHistories":[]}}""")?.records)
        assertEquals(emptyList(), serviceHistory("""{"payload":{}}""")?.records)
    }

    @Test
    fun `notifications are flattened, newest first and the VIN is masked`() {
        val payload = """
            {"payload":[
              {"vin":"VIN1234567890","notifications":[
                {"notificationDate":"2026-11-01T10:00:00Z","message":"Serwis dla VIN1234567890",
                 "displayCategory":"Service","category":"OTHER","type":"INFO","isRead":true},
                {"notificationDate":"2026-11-02T08:30:00+01:00","message":"Nowe powiadomienie","isRead":false}
              ]},
              {"vin":"VIN1234567890","notifications":null}
            ]}
        """.trimIndent()
        val list = notifications(payload)

        assertEquals(2, list?.size)
        val newest = list?.first()
        assertEquals(Instant.parse("2026-11-02T07:30:00Z"), newest?.date)
        assertEquals("Nowe powiadomienie", newest?.message)
        assertEquals(false, newest?.isRead)
        val older = list?.last()
        assertEquals("Serwis dla *********7890", older?.message)
        assertEquals("Service", older?.category)
        assertEquals("INFO", older?.type)
        assertEquals(true, older?.isRead)
        // No VIN anywhere in the domain model of a notification.
        assertTrue(list.orEmpty().none { it.message?.contains("VIN1234567890") == true })
    }

    @Test
    fun `an account without notifications is no data, an empty page is an empty list`() {
        assertNull(notifications("""{"payload":null}"""))
        assertEquals(emptyList(), notifications("""{"payload":[]}"""))
        assertEquals(emptyList(), notifications("""{"payload":[{"notifications":[]}]}"""))
    }

    @Test
    fun `a missing date and a missing category stay unknown`() {
        val list = notifications(
            """{"payload":[{"notifications":[{"message":"x"}]}]}""",
            vin = null,
        )

        assertNull(list?.first()?.date)
        assertNull(list?.first()?.category)
        assertNull(list?.first()?.isRead)
        assertEquals("x", list?.first()?.message)
    }
}
