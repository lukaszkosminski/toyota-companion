package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Synchronization status with "Synchronizuj teraz" and "Wyloguj"
 * (docs/SPEC.md §9.3). Only the kind of the last error is shown — the stored
 * message is never rendered (docs/SECURITY.md).
 */
@Composable
fun StatusScreen(state: UiState.LoggedIn, onSyncNow: () -> Unit, onLogout: () -> Unit) {
    val status = state.status
    val error = state.lastSyncFailed ?: status?.state?.lastErrorKind

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Texts.STATUS_TITLE, style = MaterialTheme.typography.headlineSmall)
        if (status == null) {
            Text(Texts.NO_DATA)
        } else {
            Text(Texts.trips(status.tripsStored))
            Text(Texts.readings(status.readingsStored))
            Text(Texts.pendingRefuels(status.pendingRefuels))
            Text("Dane: ${Texts.freshness(status)}")
            Text(Texts.lastSuccess(status))
        }
        if (state.syncing) Text(Texts.SYNCING)
        error?.let { Text(Texts.syncError(it), color = MaterialTheme.colorScheme.error) }
        if (status != null && status.state.needsLogin) {
            Text(Texts.RELOGIN_HINT, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onSyncNow, enabled = !state.syncing) { Text(Texts.SYNC_NOW_BUTTON) }
        OutlinedButton(onClick = onLogout) { Text(Texts.LOGOUT_BUTTON) }
    }
}
