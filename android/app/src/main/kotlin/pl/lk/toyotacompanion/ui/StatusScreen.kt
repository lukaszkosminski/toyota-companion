package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncStatus

/**
 * Synchronization status (docs/SPEC.md §9.3): what is stored locally, when it was
 * refreshed, and the two actions the user has — sync now and log out.
 *
 * Nothing here calls `refresh_status()` (docs/SPEC.md §9.3): pulling to refresh
 * re-reads the local archive, "Synchronizuj teraz" starts a normal sync job.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    state: UiState.LoggedIn,
    onSyncNow: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.APP_NAME) },
                actions = { TextButton(onClick = { confirmLogout = true }) { Text(Texts.LOGOUT_BUTTON) } },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val status = state.status
                if (status == null) {
                    if (state.syncing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(Texts.LOADING)
                        }
                    }
                } else {
                    StatusChip(status, state.lastSyncFailed)
                    StatRow(status)
                    Text(Texts.lastSuccess(status), style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                }

                if (state.lastSyncFailed == SyncErrorKind.LOGIN ||
                    state.lastSyncFailed == SyncErrorKind.NO_CREDENTIALS
                ) {
                    ErrorCard(Texts.RELOGIN_HINT)
                } else if (state.lastSyncFailed != null) {
                    ErrorCard(Texts.syncError(state.lastSyncFailed))
                }

                FilledTonalButton(
                    onClick = onSyncNow,
                    enabled = !state.syncing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(if (state.syncing) Texts.SYNCING else Texts.SYNC_NOW_BUTTON)
                }
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(Texts.REFRESH_BUTTON)
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(Texts.LOGOUT_CONFIRM_TITLE) },
            text = { Text(Texts.LOGOUT_CONFIRM_BODY) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) {
                    Text(Texts.LOGOUT_CONFIRM_YES)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(Texts.LOGOUT_CONFIRM_NO) }
            },
        )
    }
}

/** Fresh / stale / never / relogin — one chip, so the state is readable at a glance. */
@Composable
private fun StatusChip(status: SyncStatus, failure: SyncErrorKind?) {
    val scheme = MaterialTheme.colorScheme
    val label = when {
        status.state.needsLogin -> Texts.STATE_NEEDS_RELOGIN
        status.state.lastSuccessAt == null -> Texts.STATE_NEVER
        else -> Texts.freshness(status)
    }
    val container = when {
        status.state.needsLogin || failure == SyncErrorKind.LOGIN -> scheme.errorContainer
        status.state.lastSuccessAt == null -> scheme.surfaceVariant
        status.isFresh -> scheme.primaryContainer
        else -> scheme.secondaryContainer
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = container,
            labelColor = scheme.onSurface,
        ),
    )
}

/** The counts as numbers with a caption; the card makes the row readable as one block. */
@Composable
private fun StatRow(status: SyncStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Stat(status.tripsStored, Texts.STAT_TRIPS)
            Stat(status.readingsStored, Texts.STAT_READINGS)
            Stat(status.pendingRefuels, Texts.STAT_PENDING_REFUELS)
        }
    }
}

@Composable
private fun RowScope.Stat(value: Int, caption: String) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(caption, style = MaterialTheme.typography.bodySmall)
    }
}
