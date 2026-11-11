package pl.lk.toyotacompanion.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.lk.toyotacompanion.core.domain.SummaryPeriod

/**
 * The owner's choice in "Opcje" for the period summary (PRD F54, docs/SPEC.md §54): the period, or `null` when it is off. The value is a phone
 * setting; [set] stores it and changes what the screen shows. The store also drops the remembered key on a change, so the next summary starts
 * from a baseline.
 */
class PeriodSummarySettings(
    private val read: () -> SummaryPeriod?,
    private val save: (SummaryPeriod?) -> Unit,
) {
    private val ui = MutableStateFlow(read())
    val state: StateFlow<SummaryPeriod?> = ui.asStateFlow()

    fun set(kind: SummaryPeriod?) {
        save(kind)
        ui.value = kind
    }
}
