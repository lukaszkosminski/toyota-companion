package pl.lk.toyotacompanion.car

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lk.toyotacompanion.widget.WidgetSnapshot

/** docs/SPEC.md §35: what the Android Auto screen shows and that the service is declared for the IoT category. */
@RunWith(RobolectricTestRunner::class)
class CarRowsTest {
    @Test
    fun `full data gives three rows in order`() {
        val rows = carRows(WidgetSnapshot("4,60 l/100 km", "↗ rosnące (+8,2 %) · cena 6,30 zł/l", "Tankowanie za ok. 30 dni"))
        assertEquals(listOf("Średnie spalanie", "Trend i cena", "Tankowanie"), rows.map { it.title })
        assertEquals("4,60 l/100 km", rows[0].text)
    }

    @Test
    fun `an empty line is not a row and no consumption is one row that asks to open the app`() {
        assertEquals(1, carRows(WidgetSnapshot("4,60 l/100 km", "", "")).size)
        val empty = carRows(WidgetSnapshot("", "", ""))
        assertEquals(1, empty.size)
        assertEquals("Brak danych", empty.single().title)
        assertTrue(empty.single().text.contains("telefonie"))
    }

    @Test
    fun `rows carry no position and no vin`() {
        val text = carRows(WidgetSnapshot("4,60 l/100 km", "→ stabilne (+1,0 %)", "Tankowanie za ok. 9 dni")).joinToString { it.title + it.text }
        assertFalse(text.contains("VIN", ignoreCase = true) || text.contains("54."))
    }

    @Test
    fun `the car app service is declared for the IoT category`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent("androidx.car.app.CarAppService").addCategory("androidx.car.app.category.IOT").setPackage(context.packageName)
        val services = context.packageManager.queryIntentServices(intent, 0)
        assertEquals(1, services.size)
        val info = assertNotNull(services.single().serviceInfo)
        assertEquals(ConsumptionCarAppService::class.java.name, info.name)
        assertTrue(info.exported)
    }
}
