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
    fun `the line has only the trips and the distance so it fits a narrow screen`() {
        assertEquals("24 trasy · 312,4 km", WeatherFormatting.bucketLine(stats()))
        assertEquals("1 trasa · 312,4 km", WeatherFormatting.bucketLine(stats(trips = 1)))
        assertEquals("5 tras · 312,4 km", WeatherFormatting.bucketLine(stats(trips = 5)))
    }

    @Test
    fun `the price per 100 km moves to the helper line and without a price it says so`() {
        assertEquals("22,10 zł/100 km", WeatherFormatting.bucketCost(stats()))
        assertEquals(Texts.NO_PRICE, WeatherFormatting.bucketCost(stats(cost100 = null)))
        assertEquals("22,10 zł/100 km · +0,61 l/100 km wobec odniesienia · na prądzie 50 %", WeatherFormatting.bucketDetails(stats(delta = "0.612")))
        assertEquals("22,10 zł/100 km · na prądzie 50 %", WeatherFormatting.bucketDetails(stats()))
        assertTrue(WeatherFormatting.bucketDetails(stats(cost100 = null)).startsWith(Texts.NO_PRICE))
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
                assertEquals("24 trasy · 312,4 km", WeatherFormatting.bucketLine(stats()))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `every bucket of the domain has a Polish name`() {
        for (name in listOf("deep_frost", "frost", "cold", "mild", "warm", "dry", "wet")) {
            assertTrue(Texts.weatherBucketName(name) != name, name)
        }
    }

    @Test
    fun `the screen carries the attribution the licence asks for`() {
        assertTrue(Texts.WEATHER_ATTRIBUTION.contains("Open-Meteo.com"))
        assertTrue(Texts.WEATHER_ATTRIBUTION.contains("CC BY 4.0"))
    }

    @Test
    fun `an electric part above the whole is cut to 100 percent`() {
        assertEquals("100 %", WeatherFormatting.evShare(stats(km = "10.0", ev = "10.4")))
    }

    @Test
    fun `the wind of the bucket is shown, not judged`() {
        val note = WeatherFormatting.bucketNote(stats().copy(windMaxKmh = BigDecimal("61.2")))
        assertTrue(note.orEmpty().contains("wiatr do 61 km/h"))
    }

    @Test
    fun `every text of the weather screen is free of secrets and every fetch result has one`() {
        val texts = listOf(
            Texts.WEATHER_TITLE, Texts.WEATHER_READ_ERROR, Texts.WEATHER_HINT, Texts.WEATHER_FETCH, Texts.WEATHER_FETCHING,
            Texts.WEATHER_BY_TEMPERATURE, Texts.WEATHER_BY_RAIN, Texts.WEATHER_NO_DATA, Texts.WEATHER_TOO_FEW,
            Texts.WEATHER_CAVEAT, Texts.WEATHER_ATTRIBUTION, Texts.weatherPending(0, 0, 5), Texts.weatherPending(3, 0, 5),
            Texts.weatherPending(12, 2, 5), Texts.weatherWithout(2, 9), Texts.weatherDelta("+0,61"),
        ) + WeatherFetchResult.entries.map { Texts.weatherFetchResult(it) }
        for (text in texts) {
            assertTrue(text.isNotBlank())
            assertTrue(!text.contains("VINAAA", ignoreCase = true) && !text.contains("54.3"), text)
        }
        assertEquals(WeatherFetchResult.entries.size, WeatherFetchResult.entries.map { Texts.weatherFetchResult(it) }.toSet().size)
    }
}
