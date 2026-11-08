package pl.lk.toyotacompanion.core.livecheck

import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pl.lk.toyotacompanion.core.toyota.ToyotaApiException
import pl.lk.toyotacompanion.core.toyota.ToyotaConst
import pl.lk.toyotacompanion.core.toyota.ToyotaController

/**
 * Structure probe of the Toyota read endpoints (TOYOTA_PROBE=structure for `:core:liveCheck`): for each read-only `GET` it prints
 * the HTTP result and the SHAPE of the answer - field names, JSON types, array sizes - and never a value, except `true`/`false`
 * (a flag cannot carry a VIN or a position). Strings and numbers are shown as `string` / `number`, with `""` marked empty.
 * So the output has no VIN, no coordinates, no timestamps, no names and can be pasted into a chat.
 *
 * Reads only: no `refresh_status`, no wake, no command, no climate or charging request. Never part of CI.
 */
internal suspend fun probeStructure(controller: ToyotaController) {
    val json = Json { ignoreUnknownKeys = true }
    println("Sonda struktury odpowiedzi Toyoty (tylko odczyty, bez wartosci poza true/false).")

    println("\n[1] /v2/vehicle/guid (lista pojazdow)")
    val guid = fetch(controller, ToyotaConst.VEHICLE_GUID_ENDPOINT, null, json)
    val vin = guid?.let { firstVin(it) }
    if (vin == null) {
        println("Brak VIN w odpowiedzi - koniec.")
        return
    }

    val today = LocalDate.now(ZoneOffset.UTC)
    var tripId: String? = null
    val endpoints = listOf(
        "[2] /v3/telemetry (telemetria)" to ToyotaConst.TELEMETRY_ENDPOINT,
        "[3] /v1/vehicle/status (stan drzwi, okien, swiatel; dane z chmury)" to "/v1/vehicle/status",
        "[4] /v1/vehiclehealth/status (stan techniczny)" to "/v1/vehiclehealth/status",
        "[5] /v1/location (ostatnia pozycja; tylko ksztalt)" to "/v1/location",
        "[6] /v2/notification/history (powiadomienia)" to "/v2/notification/history",
        "[7] /v1/servicehistory/vehicle/summary (historia serwisowa)" to "/v1/servicehistory/vehicle/summary",
        "[8] /v1/trips (ostatnie 14 dni, 3 trasy, podsumowanie)" to ToyotaConst.TRIPS_ENDPOINT
            .replace("{from_date}", today.minusDays(14).toString())
            .replace("{to_date}", today.toString())
            .replace("{summary}", "true")
            .replace("{limit}", "3")
            .replace("{offset}", "0"),
    )
    for ((title, endpoint) in endpoints) {
        println("\n$title")
        val body = fetch(controller, endpoint, vin, json)
        if (endpoint.startsWith("/v1/trips") && body != null) tripId = firstTripId(body)
    }

    val id = tripId
    if (id == null) {
        println("\n[9] /v1/trips/{id}: brak trasy w ostatnich 14 dniach.")
    } else {
        println("\n[9] /v1/trips/{id} (szczegoly jednej trasy, bez przebiegu GPS: tylko ksztalt)")
        fetch(controller, ToyotaConst.TRIP_DETAIL_ENDPOINT.replace("{trip_id}", id), vin, json)
    }
    println("\nGOTOWE. Nic z powyzszego nie zawiera VIN, pozycji ani wartosci poza true/false.")
}

private suspend fun fetch(controller: ToyotaController, endpoint: String, vin: String?, json: Json): JsonElement? {
    val body = try {
        controller.request("GET", endpoint, vin)
    } catch (e: ToyotaApiException) {
        // Only the status: the body of an error could echo an identifier.
        println("    HTTP ${e.statusCode} - brak danych z tego endpointu")
        return null
    }
    val element = try {
        json.parseToJsonElement(body)
    } catch (e: Exception) {
        println("    HTTP 200, ale odpowiedz nie jest JSON-em (dlugosc ${body.length})")
        return null
    }
    println("    HTTP 200")
    printShape(element, 1)
    return element
}

/** The first VIN of the vehicle list, only to ask the other endpoints; never printed. */
private fun firstVin(root: JsonElement): String? = runCatching {
    root.jsonObject["payload"]!!.jsonArray.firstNotNullOfOrNull { it.jsonObject["vin"]?.jsonPrimitive?.content }
}.getOrNull()

private fun firstTripId(root: JsonElement): String? = runCatching {
    root.jsonObject["payload"]!!.jsonObject["trips"]!!.jsonArray.firstNotNullOfOrNull { it.jsonObject["id"]?.jsonPrimitive?.content }
}.getOrNull()

/** Prints the shape with two spaces per level; an array shows its size and the union of the keys of its objects. */
private fun printShape(element: JsonElement, depth: Int) {
    val pad = "    ".repeat(depth)
    when (element) {
        is JsonObject -> for ((key, value) in element) {
            when (value) {
                is JsonObject -> {
                    println("$pad$key: object (${value.size} pol)")
                    printShape(value, depth + 1)
                }
                is JsonArray -> {
                    println("$pad$key: array (${value.size} elementow)")
                    printShape(merge(value), depth + 1)
                }
                else -> println("$pad$key: ${leaf(value)}")
            }
        }
        else -> println("$pad${leaf(element)}")
    }
}

/** One object whose keys are the union over the objects of the array (the first value seen per key); scalars collapse to one. */
private fun merge(array: JsonArray): JsonElement {
    val objects = array.filterIsInstance<JsonObject>()
    if (objects.isEmpty()) return array.firstOrNull() ?: JsonNull
    val merged = LinkedHashMap<String, JsonElement>()
    for (obj in objects) for ((key, value) in obj) {
        val seen = merged[key]
        // Prefer a non-null sample so the type is known; two objects under one key merge their own keys.
        merged[key] = when {
            seen == null || seen is JsonNull -> value
            seen is JsonObject && value is JsonObject -> JsonObject(seen + value.filterKeys { it !in seen.keys })
            else -> seen
        }
    }
    return JsonObject(merged)
}

private fun leaf(value: JsonElement): String = when (value) {
    is JsonNull -> "null"
    is JsonPrimitive -> when {
        value.isString -> if (value.content.isEmpty()) "string (pusty)" else "string"
        value.content == "true" || value.content == "false" -> value.content
        else -> "number"
    }
    else -> "?"
}
