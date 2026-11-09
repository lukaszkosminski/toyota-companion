package pl.lk.toyotacompanion.core.toyota.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire models of the Toyota notification history (PRD F45, docs/SPEC.md §46). Mirrors pytoyoda
 * `models/endpoints/notifications.py`; every field is nullable and unknown keys are ignored.
 *
 * `payload` is a list of per-vehicle pages and is `null` on an account without notifications
 * (pytoyoda raises `TypeError` there), so both the list and the nested `notifications` stay nullable:
 * the mapper treats that as "no data", never as an error.
 */

/** pytoyoda notifications.py:68-90 — GET /v2/notification/history. */
@Serializable
data class NotificationHistoryResponse(val payload: List<NotificationPageDto?>? = null)

/** pytoyoda notifications.py:55-66: one page per vehicle. */
@Serializable
data class NotificationPageDto(
    val vin: String? = null,
    val notifications: List<NotificationDto?>? = null,
)

/** pytoyoda notifications.py:22-53. `status` is a union of an int and a string there, so it stays raw. */
@Serializable
data class NotificationDto(
    @SerialName("messageId") val messageId: String? = null,
    val vin: String? = null,
    @SerialName("notificationDate") val notificationDate: String? = null,
    @SerialName("isRead") val isRead: Boolean? = null,
    @SerialName("readTimestamp") val readTimestamp: String? = null,
    @SerialName("iconUrl") val iconUrl: String? = null,
    val message: String? = null,
    val status: JsonElement? = null,
    val type: String? = null,
    val category: String? = null,
    @SerialName("displayCategory") val displayCategory: String? = null,
)
