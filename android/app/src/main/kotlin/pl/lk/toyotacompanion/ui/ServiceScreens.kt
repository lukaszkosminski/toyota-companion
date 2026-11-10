package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
                        serviceDetails(row).forEach { LabelValueRow(it) }
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
            notifications.forEach { NotificationCard(it, zone) }
        }
    }
}

/**
 * One notification (PRD F45, docs/SPEC.md §46). An unread one is marked twice: its date is bold and its icon and a small label take the
 * accent colour; a read one stays quiet. The masked VIN in front of the message is not shown.
 */
@Composable
private fun NotificationCard(notification: ToyotaNotification, zone: ZoneId) {
    val unread = notification.isRead == false
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    AppIcons.Info,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (unread) readable(Palette.Blue) else scheme.onSurfaceVariant,
                )
                Text(
                    notificationDateText(notification.date, zone),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else null,
                )
                if (unread) {
                    Pill(Texts.NOTIFICATIONS_UNREAD, Palette.Blue.copy(alpha = 0.18f), Palette.Blue)
                }
            }
            if (!unread) notificationReadText(notification.isRead)?.let { MutedLine(it) }
            notificationKindText(notification)?.let { MutedLine(it) }
            notificationMessageText(notification.message)?.let { DetailLine(it) }
        }
    }
}
