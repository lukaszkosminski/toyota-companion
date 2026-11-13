package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import pl.lk.toyotacompanion.core.domain.SummaryPeriod
import pl.lk.toyotacompanion.settings.CarValuation

/** "Opcje": settings of the app itself: the language, applied at once and remembered, the default fuel price (§12.3) and the app update (§37). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(
    update: UpdateController,
    onSetLanguage: (AppLanguage) -> Unit,
    defaultPrice: BigDecimal?,
    onShowDefaultPrice: () -> Unit,
    periodSummary: SummaryPeriod?,
    onSetPeriodSummary: (SummaryPeriod?) -> Unit,
    carValuation: CarValuationUiState,
    onSaveCarValuation: (CarValuation) -> Unit,
    onRemoveCarValuation: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val updateState by update.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.OPTIONS_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        // The cards no longer fit one screen (F54, F55 added two), so the column scrolls.
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardCard(Texts.LANGUAGE_TITLE, AppIcons.Language) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (language in AppLanguage.entries) {
                        FilterChip(
                            selected = Lang.current == language,
                            onClick = { onSetLanguage(language) },
                            label = { Text(language.label) },
                        )
                    }
                }
            }
            // The price of the fuel in the tank before the first confirmed refuel: it prices the trips before it and mixes into the first refuel (docs/SPEC.md §4, §12).
            DashboardCard(Texts.DEFAULT_PRICE_TITLE, AppIcons.Payments) {
                Text(Texts.DEFAULT_PRICE_EXPLAINED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onShowDefaultPrice) { Text(Texts.defaultPricePrompt(defaultPrice)) }
            }
            // The owner's car valuation: a phone setting, never the archive (PRD F55, docs/SPEC.md §55).
            CarValuationCard(state = carValuation, onSave = onSaveCarValuation, onRemove = onRemoveCarValuation)
            // The period summary notification (PRD F54, docs/SPEC.md §54): off by default; the period is a week or a month.
            DashboardCard(Texts.PERIOD_SUMMARY_TITLE, AppIcons.Info) {
                Text(
                    Texts.PERIOD_SUMMARY_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = periodSummary != null, onCheckedChange = { on -> onSetPeriodSummary(if (on) SummaryPeriod.WEEK else null) })
                    Text(Texts.PERIOD_SUMMARY_SWITCH, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                if (periodSummary != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (kind in SummaryPeriod.entries) {
                            FilterChip(
                                selected = periodSummary == kind,
                                onClick = { onSetPeriodSummary(kind) },
                                label = { Text(Texts.periodKindName(kind)) },
                            )
                        }
                    }
                }
            }
            DashboardCard(Texts.UPDATE_TITLE, AppIcons.SystemUpdate) {
                UpdateCardContent(
                    state = updateState,
                    onCheck = update::check,
                    onInstall = update::install,
                    onAllow = update::openInstallPermissionSettings,
                )
            }
        }
    }
}

@Composable
private fun UpdateCardContent(state: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit, onAllow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Texts.updateCurrent(state.currentVersion), style = MaterialTheme.typography.bodyMedium)
        val release = state.release
        val message = when (state.phase) {
            UpdatePhase.IDLE -> null
            UpdatePhase.CHECKING -> Texts.UPDATE_CHECKING
            UpdatePhase.UP_TO_DATE -> Texts.UPDATE_UP_TO_DATE
            UpdatePhase.AVAILABLE -> release?.let { Texts.updateAvailable(it.versionName) }
            UpdatePhase.NEEDS_PERMISSION -> Texts.UPDATE_NEEDS_PERMISSION
            UpdatePhase.DOWNLOADING -> Texts.updateDownloading(state.downloaded, release?.apkSize ?: 0)
            UpdatePhase.INSTALLING -> Texts.UPDATE_INSTALLING
            UpdatePhase.FAILED -> state.failure?.let(Texts::updateFailure)
        }
        if (message != null) {
            val color = if (state.phase == UpdatePhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            Text(message, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        // INSTALLING is not busy: when the system confirmation never shows up, "Pobierz i zainstaluj" starts over.
        val busy = state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (release != null && !busy) {
                if (state.phase == UpdatePhase.NEEDS_PERMISSION) {
                    OutlinedButton(onClick = onAllow) { Text(Texts.UPDATE_ALLOW) }
                }
                Button(onClick = onInstall) { Text(Texts.UPDATE_INSTALL) }
            } else {
                OutlinedButton(onClick = onCheck, enabled = !busy) { Text(Texts.UPDATE_CHECK) }
            }
        }
        Text(Texts.UPDATE_SOURCE_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
