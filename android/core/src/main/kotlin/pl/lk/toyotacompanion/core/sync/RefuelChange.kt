package pl.lk.toyotacompanion.core.sync

import pl.lk.toyotacompanion.core.domain.Refuel

/**
 * Result of editing one refuel entry (docs/SPEC.md §11.1). Outcomes, never
 * exceptions — the same rule as [SyncOutcome] (docs/SPEC.md §8.5), so the UI
 * maps every case onto a Polish message without catching anything.
 *
 * The reason of a rejected price is deliberately not carried: the domain says
 * it in an English `IllegalArgumentException`, and the app form already checks
 * the combination and the sign itself (docs/SPEC.md §11.2). No exception text
 * ever reaches the screen (docs/SECURITY.md).
 */
sealed class RefuelChange {
    /**
     * The entry was stored and the costs of the vehicle were recomputed
     * (PRD F4).
     *
     * @param refuel the stored row: `CONFIRMED` or `DISMISSED`, with the values
     * the domain derived
     * @param tripsCosted how many trips of the vehicle were costed, not how many
     * rows changed; the UI shows nothing from it
     */
    data class Done(val refuel: Refuel, val tripsCosted: Int) : RefuelChange()

    /** No such entry — or an entry of another vehicle, which this screen never sees. */
    data object NotFound : RefuelChange()

    /** The entry exists but is already confirmed or dismissed, so nothing was written. */
    data object NotPending : RefuelChange()

    /** The domain refused the values; nothing was written and nothing was recomputed. */
    data object Invalid : RefuelChange()

    /** No vehicle selected, so there is no archive to write to. */
    data object NoVehicle : RefuelChange()
}
