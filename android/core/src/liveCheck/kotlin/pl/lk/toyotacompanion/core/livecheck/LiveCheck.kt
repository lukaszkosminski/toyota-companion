package pl.lk.toyotacompanion.core.livecheck

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import pl.lk.toyotacompanion.core.toyota.ToyotaApi
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException
import pl.lk.toyotacompanion.core.toyota.ToyotaController
import pl.lk.toyotacompanion.core.toyota.ToyotaInvalidUsernameException
import pl.lk.toyotacompanion.core.toyota.ToyotaLoginException
import pl.lk.toyotacompanion.core.toyota.ToyotaMapper
import pl.lk.toyotacompanion.core.toyota.ToyotaNetworkException

/**
 * Manual check of the Toyota client against a real MyToyota account.
 *
 * Run by the account owner only:
 *     read -rp "Login: " TOYOTA_USERNAME; read -rsp "Haslo: " TOYOTA_PASSWORD; echo
 *     export TOYOTA_USERNAME TOYOTA_PASSWORD
 *     ./gradlew -q :core:liveCheck
 *
 * Reads only (no refresh_status, no commands). Prints no password, token, full
 * VIN or GPS position. Never part of CI.
 */
fun main() {
    val username = System.getenv("TOYOTA_USERNAME").orEmpty()
    val password = System.getenv("TOYOTA_PASSWORD").orEmpty()
    if (username.isBlank() || password.isBlank()) {
        System.err.println("Ustaw TOYOTA_USERNAME i TOYOTA_PASSWORD (patrz komentarz w LiveCheck.kt).")
        exitProcess(2)
    }
    val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    val code = try {
        runBlocking { check(ToyotaApi(ToyotaController(username, password, http))) }
        0
    } catch (e: ToyotaInvalidUsernameException) {
        println("BLAD logowania: Toyota nie zna tego loginu.")
        3
    } catch (e: ToyotaLoginException) {
        println("BLAD logowania: ${e.message}")
        3
    } catch (e: ToyotaApiException) {
        println("BLAD API Toyoty: HTTP ${e.statusCode} ${e.message}")
        4
    } catch (e: ToyotaNetworkException) {
        println("BLAD sieci: ${e.message}")
        5
    } finally {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
    exitProcess(code)
}

private fun maskVin(vin: String): String = "*".repeat((vin.length - 4).coerceAtLeast(0)) + vin.takeLast(4)

private suspend fun check(api: ToyotaApi) {
    println("1/3 Logowanie i lista pojazdow...")
    val vehicles = api.getVehicles()
    println("    OK: ${vehicles.size} pojazd(y)")
    vehicles.forEach { println("    - ${maskVin(it.vin.orEmpty())} ${it.modelName ?: "?"} paliwo=${it.fuelType ?: "?"}") }
    val vin = vehicles.firstNotNullOfOrNull { it.vin } ?: run {
        println("    Brak VIN na koncie - koniec.")
        return
    }

    println("2/3 Telemetria (${maskVin(vin)})...")
    val reading = ToyotaMapper.toFuelReading(api.getTelemetry(vin))
    if (reading == null) {
        println("    Telemetria bez poziomu paliwa/przebiegu (mapowanie zwrocilo null).")
    } else {
        println(
            "    OK: paliwo ${reading.levelPct}%, przebieg ${reading.odometerKm} km, " +
                "zasieg ${reading.distanceToEmptyKm ?: "?"} km, odczyt z ${reading.ts}",
        )
    }

    println("3/3 Trasy z ostatnich 30 dni...")
    val today = LocalDate.now(ZoneOffset.UTC)
    val dtos = api.fetchAllTrips(vin, today.minusDays(30), today)
    val trips = dtos.mapNotNull { ToyotaMapper.toDomainTrip(it) }
    println("    OK: ${dtos.size} tras z API, ${trips.size} zmapowanych")
    trips.sortedByDescending { it.start }.take(5).forEach { t ->
        val avg = if (t.distanceKm > 0) {
            t.fuelL.multiply(BigDecimal(100)).divide(BigDecimal(t.distanceKm.toString()), 2, RoundingMode.HALF_UP)
        } else {
            null
        }
        println(
            "    - ${t.start}: ${"%.1f".format(t.distanceKm)} km, ${t.fuelL} l, " +
                "${avg ?: "?"} l/100km, EV ${t.evDistanceKm?.let { "%.1f".format(it) } ?: "?"} km",
        )
    }
    println("GOTOWE: klient Toyoty dziala na tym koncie.")
}
