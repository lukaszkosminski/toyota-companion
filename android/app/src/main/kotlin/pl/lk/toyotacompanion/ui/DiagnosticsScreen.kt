package pl.lk.toyotacompanion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.SyncStatus

/**
 * "Diagnostyka synchronizacji" (PRD F46, docs/SPEC.md §47): when the last steps of a synchronization worked, when
 * Toyota last saw the car, the last error with its HTTP status and the archive counters. A local read — no Toyota
 * call, so the car is never woken — and nothing is stored. The same lines can be copied or shared as a report that
 * carries no VIN, GPS or token.
 */
@Composable
fun DiagnosticsScreen(
    controller: RemoteDataController<SyncStatus>,
    appVersion: String,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.load() }
    val status = state.data
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    RemoteScreen(Texts.DIAGNOSTICS_TITLE, onBack) {
        RemoteState(state.loading, state.unavailable, state.failed, status != null, Texts.DIAGNOSTICS_READ_ERROR) {
            controller.load()
        }
        if (status != null) {
            val report = diagnosticsReport(status, appVersion, zone)
            // The rows of the screen are label and value; the copied and shared text stays the plain report (docs/SPEC.md §47).
            DashboardCard(Texts.DIAGNOSTICS_TITLE, AppIcons.FactCheck) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    diagnosticsRows(status, appVersion, zone, LocalDate.now(zone)).forEach { LabelValueRow(it) }
                }
            }
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(Texts.DIAGNOSTICS_TITLE, report))
                    copied = true
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                IconLabel(AppIcons.ContentCopy, Texts.DIAGNOSTICS_COPY)
            }
            if (copied) {
                Text(Texts.DIAGNOSTICS_COPIED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, report)
                    context.startActivity(Intent.createChooser(send, Texts.DIAGNOSTICS_SHARE))
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                IconLabel(AppIcons.Share, Texts.DIAGNOSTICS_SHARE)
            }
            Text(Texts.DIAGNOSTICS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
