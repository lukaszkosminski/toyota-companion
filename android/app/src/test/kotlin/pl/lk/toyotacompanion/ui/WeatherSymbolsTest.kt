package pl.lk.toyotacompanion.ui

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.weather.WeatherDay

private fun day(mean: Double?, code: Int?) = WeatherDay(LocalDate.parse("2026-10-30"), mean, null, null, null, null, null, code)

/** docs/SPEC.md §10.3: the symbol, word and temperature of the day on a trip row. */
class WeatherSymbolsTest {
    @Test
    fun `clear, rain, snow and storm have a symbol and a Polish word`() {
        assertEquals("☀️ słonecznie · 12 °C", WeatherSymbols.line(day(12.4, 0)))
        assertEquals("🌧️ deszcz · 8 °C", WeatherSymbols.line(day(7.6, 63)))
        assertEquals("❄️ śnieg · -2 °C", WeatherSymbols.line(day(-1.6, 73)))
        assertEquals("⛈️ burza · 21 °C", WeatherSymbols.line(day(20.5, 95)))
        assertEquals("☁️ pochmurno · 10 °C", WeatherSymbols.line(day(10.0, 3)))
    }

    @Test
    fun `every code the service uses has a symbol and a word`() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        for (code in codes) {
            assertEquals(false, WeatherSymbols.symbol(code).isNullOrEmpty(), "symbol $code")
            assertEquals(false, WeatherSymbols.word(code).isNullOrEmpty(), "word $code")
        }
        assertNull(WeatherSymbols.symbol(42))
        assertNull(WeatherSymbols.symbol(null))
    }

    @Test
    fun `without a code only the temperature, without a temperature only the word, without both nothing`() {
        assertEquals("5 °C", WeatherSymbols.line(day(5.0, null)))
        assertEquals("🌧️ deszcz", WeatherSymbols.line(day(null, 61)))
        assertNull(WeatherSymbols.line(day(null, null)))
        assertNull(WeatherSymbols.line(null))
    }

    @Test
    fun `a mean just under zero is zero degrees, never minus zero`() {
        assertEquals("0 °C", WeatherSymbols.line(day(-0.3, null)))
        assertEquals("0 °C", WeatherSymbols.line(day(0.4, null)))
        assertEquals("-1 °C", WeatherSymbols.line(day(-0.5, null)))
    }
}
