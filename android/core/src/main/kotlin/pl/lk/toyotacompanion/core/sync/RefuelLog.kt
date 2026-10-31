package pl.lk.toyotacompanion.core.sync

import pl.lk.toyotacompanion.core.domain.EconomyInterval
import pl.lk.toyotacompanion.core.domain.Refuel

/**
 * The refuel log of the selected vehicle (PRD F9, docs/SPEC.md §15).
 *
 * @param refuels confirmed refuels, newest first (ties by id)
 * @param intervals full-tank to full-tank intervals, oldest first, from
 *   `fuelEconomyIntervals`; the last one may be open
 */
data class RefuelLog(val refuels: List<Refuel>, val intervals: List<EconomyInterval>) {
    companion object {
        val EMPTY = RefuelLog(emptyList(), emptyList())
    }
}
