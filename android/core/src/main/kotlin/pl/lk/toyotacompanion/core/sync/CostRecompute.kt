package pl.lk.toyotacompanion.core.sync

/**
 * Result of a cost recount asked for by the UI (docs/SPEC.md §12.1), e.g. after
 * the user set or removed the default fuel price.
 *
 * Outcomes, never exceptions — the same rule as [SyncOutcome] and [RefuelChange]
 * (docs/SPEC.md §8.5), so the app maps every case onto a Polish message without
 * catching anything.
 */
sealed class CostRecompute {
    /**
     * The costs of the selected vehicle were computed again and written.
     *
     * @param tripsCosted how many trip-cost rows were saved, not how many changed;
     * the UI shows nothing from it
     */
    data class Done(val tripsCosted: Int) : CostRecompute()

    /** Nothing was recomputed because no vehicle is selected yet; the setting itself stayed. */
    data object NoVehicle : CostRecompute()
}
