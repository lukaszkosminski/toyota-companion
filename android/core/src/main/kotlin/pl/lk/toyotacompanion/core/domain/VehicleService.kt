package pl.lk.toyotacompanion.core.domain

import java.time.Instant
import java.time.LocalDate

/**
 * The service history and the notifications Toyota keeps for the car (PRD F44-F45, docs/SPEC.md §45-§46).
 * Both are read from Toyota's cloud cache only: no `refresh_status()`, no command, so the car is never
 * woken. Nothing here is stored in the database, the archive, a backup or a log.
 */

/**
 * One service record as Toyota reports it. Every field is nullable, because the payload may miss a part;
 * the screen says "no data" instead of guessing.
 *
 * [dealer] is `servicingDealer`, or `serviceProvider` when the dealer is missing (decision of the owner,
 * 2026-11-09; one label "Dealer"). [operations] null means Toyota sent no readable list; an empty list
 * means it sent an empty one.
 */
data class ServiceRecord(
    val date: LocalDate? = null,
    /** Mileage at the service; Toyota sends [mileageUnit] with it. */
    val mileage: Int? = null,
    val mileageUnit: String? = null,
    val category: String? = null,
    val operations: List<String>? = null,
    val dealer: String? = null,
)

/** The whole history, newest first. Empty [records] means "Toyota knows no entries", not "no data" (that is null). */
data class ServiceHistory(val records: List<ServiceRecord> = emptyList())

/** One Toyota notification, newest first. [message] already has the VIN masked (docs/SECURITY.md). */
data class ToyotaNotification(
    val date: Instant? = null,
    val category: String? = null,
    val type: String? = null,
    val message: String? = null,
    val isRead: Boolean? = null,
)
