package pl.lk.toyotacompanion.ui

import java.math.RoundingMode
import pl.lk.toyotacompanion.core.weather.WeatherDay

/** What the weather of a day looks like in a list: a symbol, a word and the mean temperature (docs/SPEC.md §10.3). */
object WeatherSymbols {
    /** Symbol of a WMO weather code (Open-Meteo's daily `weather_code`); `null` for no or an unknown code. */
    fun symbol(code: Int?): String? = when (code) {
        0 -> "☀️"
        1 -> "🌤️"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> null
    }

    /** Polish word for the symbol, for the screen reader and the text next to it. */
    fun word(code: Int?): String? = when (code) {
        0 -> "słonecznie"
        1 -> "prawie bezchmurnie"
        2 -> "częściowe zachmurzenie"
        3 -> "pochmurno"
        45, 48 -> "mgła"
        51, 53, 55, 56, 57 -> "mżawka"
        61, 63, 65, 66, 67 -> "deszcz"
        80, 81, 82 -> "przelotny deszcz"
        71, 73, 75, 77, 85, 86 -> "śnieg"
        95, 96, 99 -> "burza"
        else -> null
    }

    /**
     * `🌧️ deszcz · 8 °C`, `☀️ słonecznie · -2 °C`; without a code just the temperature, without a temperature the word
     * only, and `null` when the day has neither. Whole degrees (HALF_UP), a minus sign, no `Locale`.
     */
    fun line(day: WeatherDay?): String? {
        day ?: return null
        val symbol = symbol(day.weatherCode)
        val word = word(day.weatherCode)
        val temperature = day.tempMeanC?.takeIf { it.isFinite() }?.let {
            val rounded = java.math.BigDecimal(it).setScale(0, RoundingMode.HALF_UP)
            // A mean in (-0.5, 0) rounds to zero: "0 °C", never "-0 °C".
            (if (rounded.signum() == 0) "0" else rounded.toPlainString()) + " °C"
        }
        val text = listOfNotNull(word, temperature).joinToString(Texts.SEPARATOR)
        if (text.isEmpty()) return null
        return if (symbol == null) text else "$symbol $text"
    }
}
