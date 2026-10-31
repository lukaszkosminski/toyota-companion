package pl.lk.toyotacompanion.core.sync

/**
 * What one archive import added to the phone (PRD F7, docs/SPEC.md §14). Every
 * row is matched by its identity — trips and refuels by `id`, fuel readings by
 * `(vin, ts)`, vehicles by VIN — so importing the same file again adds nothing.
 *
 * @param refuelsUpdated refuels waiting for a price on the phone that the file
 *   already had priced or refused: the file knows more, so it wins.
 * @param refuelsSkipped refuels refused on the phone but priced in the file:
 *   the phone keeps the user's later decision, and the screen says how many.
 * @param otherVehicleTrips trips of a VIN other than the selected one: stored,
 *   but shown only for that vehicle; 0 when no vehicle is selected.
 */
data class ArchiveImportCounts(
    val vehiclesAdded: Int,
    val tripsAdded: Int,
    val refuelsAdded: Int,
    val refuelsUpdated: Int,
    val refuelsSkipped: Int,
    val readingsAdded: Int,
    val otherVehicleTrips: Int,
    val tripsCosted: Int,
)

/** Result of [SyncEngine.importArchive]; storage failures reach the caller as exceptions (docs/SPEC.md §8.5). */
sealed class ArchiveImport {
    data class Done(val counts: ArchiveImportCounts) : ArchiveImport()
}
