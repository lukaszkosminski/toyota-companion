package pl.lk.toyotacompanion.core.toyota.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire models of the Toyota service history (PRD F44, docs/SPEC.md §45). Mirrors pytoyoda
 * `models/endpoints/service_history.py` field aliases; every field is nullable and unknown keys are
 * ignored, so the structure probe (`TOYOTA_PROBE=structure`) can report a different shape without a rewrite.
 *
 * Only `serviceDate`, `mileage`, `unit`, `serviceCategory`, `operationsPerformed`, `servicingDealer` and
 * `serviceProvider` reach the domain: the other fields are kept here to stay tolerant, never shown
 * (docs/SPEC.md §45).
 */

/** pytoyoda service_history.py:58-66 — GET /v1/servicehistory/vehicle/summary. */
@Serializable
data class ServiceHistoryResponse(val payload: ServiceHistoryPayloadDto? = null)

@Serializable
data class ServiceHistoryPayloadDto(
    @SerialName("serviceHistories") val serviceHistories: List<ServiceHistoryDto?>? = null,
)

/** pytoyoda service_history.py:12-56. `notes`, `operationsPerformed`, `roNumber` and `servicingDealer` are `Any` there. */
@Serializable
data class ServiceHistoryDto(
    @SerialName("customerCreatedRecord") val customerCreatedRecord: Boolean? = null,
    val mileage: Int? = null,
    val notes: JsonElement? = null,
    @SerialName("operationsPerformed") val operationsPerformed: JsonElement? = null,
    @SerialName("roNumber") val roNumber: JsonElement? = null,
    @SerialName("serviceCategory") val serviceCategory: String? = null,
    @SerialName("serviceDate") val serviceDate: String? = null,
    @SerialName("serviceHistoryId") val serviceHistoryId: String? = null,
    @SerialName("serviceProvider") val serviceProvider: String? = null,
    @SerialName("servicingDealer") val servicingDealer: JsonElement? = null,
    val unit: String? = null,
)
