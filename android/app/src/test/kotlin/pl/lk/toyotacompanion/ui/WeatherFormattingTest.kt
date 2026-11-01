package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.WeatherBucketStats

private fun stats(
    trips: Int = 24,
    km: String = "312.44",
    l100: String? = "4.376",
    cost100: String? = "22.1",
    ev: String = "156.22",
    delta: String? = null,
    few: Boolean = false,
) = WeatherBucketStats(
    bucket = "cold",
    trips = trips,
    distanceKm = BigDecimal(km),
    fuelL = BigDecimal("13.67"),
    lPer100Km = l100?.let(::BigDecimal),
    costPln = BigDecimal("69.0"),
    tripsWithoutCost = 0,
    costPer100Km = cost100?.let(::BigDecimal),
    evKm = BigDecimal(ev),
    deltaLPer100Km = delta?.let(::BigDecimal),
    tooFewTrips = few,
    windMaxKmh = null,
)

/** docs/SPEC.md §23: the text of a weather bucket, in Polish and independent of the phone's locale. */
class WeatherFormattingTest {
    @Test
    fun `the line has trips, distance, consumption and price per 100 km`() {
        assertEquals("trasy: 24 · 312,4 km · 4,38 l/100 km · 22,10 zł/100 km", WeatherFormatting.bucketLine(stats()))
    }

    @Test
    fun `without a price the line says so`() {
        assertTrue(WeatherFormatting.bucketLine(stats(cost100 = null)).endsWith(Texts.NO_PRICE))
    }

    @Test
    fun `the note carries the gap with a sign, the electric share and the small-sample warning`() {
        assertEquals(
            "+0,61 l/100 km wobec odniesienia · na prądzie 50 % · za mało tras na wniosek",
            WeatherFormatting.bucketNote(stats(delta = "0.612", few = true)),
        )
        assertEquals("-0,20 l/100 km wobec odniesienia · na prądzie 50 %", WeatherFormatting.bucketNote(stats(delta = "-0.2")))
    }

    @Test
    fun `no distance gives no electric share and no note`() {
        assertNull(WeatherFormatting.evShare(stats(km = "0", ev = "0")))
        assertNull(WeatherFormatting.bucketNote(stats(km = "0", ev = "0")))
    }

    @Test
    fun `text does not depend on the locale`() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ROOT, Locale.US, Locale.GERMANY, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertEquals("trasy: 24 · 312,4 km · 4,38 l/100 km · 22,10 zł/100 km", WeatherFormatting.bucketLine(stats()))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `every bucket of the domain has a Polish name`() {
        for (name in listOf("frost", "cold", "mild", "warm", "dry", "wet")) {
            assertTrue(Texts.weatherBucketName(name) != name, name)
        }
    }

    @Test
    fun `the screen carries the attribution the licence asks for`() {
        assertTrue(Texts.WEATHER_ATTRIBUTION.contains("Open-Meteo.com"))
        assertTrue(Texts.WEATHER_ATTRIBUTION.contains("CC BY 4.0"))
    }
}
