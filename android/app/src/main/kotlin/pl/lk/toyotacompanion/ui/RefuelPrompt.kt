package pl.lk.toyotacompanion.ui

import pl.lk.toyotacompanion.core.domain.Refuel

/**
 * Screens that already hold a form or the refuel list: showing a prompt over them
 * would ask the same question twice, or interrupt an answer in progress
 * (docs/SPEC.md §13.3).
 */
val PROMPT_BLOCKED_SCREENS: Set<Screen> = setOf(Screen.Refuels, Screen.RefuelForm, Screen.DefaultPrice)

/**
 * Everything the "ask about the price now" decision needs, with no Compose and no
 * Android in sight (docs/SPEC.md §13.3).
 *
 * @param loggedIn an anonymous user is never asked (docs/SECURITY.md).
 * @param pending refuels waiting for a price, newest first (`SyncEngine.pendingRefuels`).
 * @param deferred ids the user put off with "Później" — only those, for the life of
 *   the process (docs/SPEC.md §13.3).
 * @param onFormScreen [PROMPT_BLOCKED_SCREENS] is showing.
 * @param busy a write started from the prompt itself is in flight.
 */
data class RefuelPromptInput(
    val loggedIn: Boolean,
    val pending: List<Refuel>,
    val deferred: Set<String>,
    val onFormScreen: Boolean,
    val busy: Boolean,
)

/**
 * The entry to ask about — the newest one the user has not put off — or `null`
 * when nothing should be shown (docs/SPEC.md §13.3). Pure, so every rule of the
 * requirement is a unit test.
 */
fun refuelPromptTarget(input: RefuelPromptInput): Refuel? {
    if (!input.loggedIn || input.onFormScreen || input.busy) return null
    return input.pending.firstOrNull { it.id !in input.deferred }
}
