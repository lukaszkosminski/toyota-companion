package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.ServiceHistory
import pl.lk.toyotacompanion.core.domain.ToyotaNotification

/**
 * The two read-only screens of Toyota's service history and notifications (PRD F44-F45, docs/SPEC.md §45-§46).
 * Each reads once on every opening — a plain GET of the Toyota cloud cache, so nothing polls, nothing calls
 * `refresh_status()` or a command and the car is never woken (docs/PRD.md §6). Nothing is stored on the phone.
 */

@Composable
private fun DetailLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** A label on the left and its value on the right; a long value (the operations) wraps inside its own column. */
@Composable
private fun DetailRow(detail: ServiceDetail) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(detail.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(detail.value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MutedLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ServiceHistoryScreen(
    controller: RemoteDataController<ServiceHistory>,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.load() }
    val history = state.data
    val records = history?.records.orEmpty()
    RemoteScreen(Texts.SERVICE_TITLE, onBack) {
        RemoteState(
            loading = state.loading,
            unavailable = state.unavailable,
            failed = state.failed,
            hasData = records.isNotEmpty(),
            errorText = Texts.SERVICE_READ_ERROR,
            retry = { controller.load() },
            emptyText = if (history != null && records.isEmpty()) Texts.SERVICE_EMPTY else Texts.REMOTE_EMPTY,
        )
        if (records.isNotEmpty()) {
            MutedLine(Texts.SERVICE_HINT)
            records.forEach { record ->
                val row = serviceRow(record)
                DashboardCard(row.title, AppIcons.History) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        // Label left, value right; the operations are untyped in the reference: no list, no guessed one.
                        serviceDetails(row).forEach { DetailRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationsScreen(
    controller: RemoteDataController<List<ToyotaNotification>>,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.load() }
    val notifications = state.data
    RemoteScreen(Texts.NOTIFICATIONS_TITLE, onBack) {
        RemoteState(
            loading = state.loading,
            unavailable = state.unavailable,
            failed = state.failed,
            hasData = !notifications.isNullOrEmpty(),
            errorText = Texts.NOTIFICATIONS_READ_ERROR,
            retry = { controller.load() },
            emptyText = if (notifications != null && notifications.isEmpty()) {
                Texts.NOTIFICATIONS_EMPTY
            } else {
                Texts.REMOTE_EMPTY
            },
        )
        if (!notifications.isNullOrEmpty()) {
            MutedLine(Texts.NOTIFICATIONS_HINT)
            notifications.forEach { notification ->
                DashboardCard(notificationDateText(notification.date, zone), AppIcons.Info) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        notificationReadText(notification.isRead)?.let { DetailLine(it) }
                        notificationKindText(notification)?.let { MutedLine(it) }
                        notification.message?.let { DetailLine(it) }
                    }
                }
            }
        }
    }
}
