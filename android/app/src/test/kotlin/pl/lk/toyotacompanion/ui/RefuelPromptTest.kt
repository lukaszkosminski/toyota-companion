package pl.lk.toyotacompanion.ui

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.RefuelStatus

private val TS: Instant = Instant.parse("2026-10-29T13:00:00Z")

private fun refuel(id: String): Refuel = Refuel(
    id = id,
    ts = TS,
    odometerKm = 12_345.0,
    levelBefore = 20,
    levelAfter = 80,
    liters = null,
    pricePerLiter = null,
    totalCost = null,
    status = RefuelStatus.PENDING,
)

private fun input(
    loggedIn: Boolean = true,
    pending: List<Refuel> = listOf(refuel("r1")),
    deferred: Set<String> = emptySet(),
    onFormScreen: Boolean = false,
    busy: Boolean = false,
) = RefuelPromptInput(loggedIn, pending, deferred, onFormScreen, busy)

/**
 * The pure decision of the prompt (docs/SPEC.md §13.3): no Compose, no Android, so
 * every clause of the requirement is one test.
 */
class RefuelPromptTest {

    @Test
    fun `the newest waiting entry is asked about`() {
        val target = refuelPromptTarget(input(pending = listOf(refuel("nowy"), refuel("stary"))))

        assertEquals("nowy", target?.id)
    }

    @Test
    fun `nobody is asked when nothing waits or when nobody is logged in`() {
        assertNull(refuelPromptTarget(input(pending = emptyList())))
        assertNull(refuelPromptTarget(input(loggedIn = false)))
    }

    @Test
    fun `a deferred entry is not asked about again but a newer one is`() {
        // The point of F2c: "Później" silences one entry, not the whole feature.
        val target = refuelPromptTarget(
            input(pending = listOf(refuel("r2"), refuel("r1")), deferred = setOf("r2")),
        )

        assertEquals("r1", target?.id)
        assertNull(refuelPromptTarget(input(pending = listOf(refuel("r1")), deferred = setOf("r1"))))
    }

    @Test
    fun `a form on screen is never interrupted`() {
        assertNull(refuelPromptTarget(input(onFormScreen = true)))
    }

    @Test
    fun `no new question while a write from the prompt is in flight`() {
        assertNull(refuelPromptTarget(input(busy = true)))
    }

    @Test
    fun `the blocked screens are the refuel list and every form`() {
        assertEquals(
            setOf(Screen.Refuels, Screen.RefuelForm, Screen.DefaultPrice),
            PROMPT_BLOCKED_SCREENS,
        )
        assertNull(refuelPromptTarget(input(onFormScreen = Screen.Refuels in PROMPT_BLOCKED_SCREENS)))
    }
}
