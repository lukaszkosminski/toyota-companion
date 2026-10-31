package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.EconomyInterval
import pl.lk.toyotacompanion.core.domain.EconomyStatus
import pl.lk.toyotacompanion.core.domain.Refuel

/**
 * "Dziennik tankowań" (PRD F9, docs/SPEC.md §15.2): confirmed refuels, newest first,
 * and under each refuel that closes a full-tank interval the "computer vs actual"
 * economy of that interval or why there is none. The open interval after the last
 * full refuel heads the list. Read only from the local archive; no VIN and no GPS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelLogScreen(
    controller: RefuelLogController,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    // Which confirmed refuel the correction dialog edits; an id is not a secret.
    var amendingId by rememberSaveable { mutableStateOf<String?>(null) }
    // The navigation callback reads the log; this only covers a screen restored after a process death.
    LaunchedEffect(Unit) { controller.ensureLoaded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.LOG_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        val log = state.log
        when {
            state.loading -> Centered(innerPadding) { CircularProgressIndicator() }
            state.loadFailed -> Centered(innerPadding) { Text(Texts.LOG_READ_ERROR) }
            log.refuels.isEmpty() -> Centered(innerPadding) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Texts.LOG_EMPTY, style = MaterialTheme.typography.bodyLarge)
                    Text(Texts.LOG_HINT, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            else -> {
                val closedBy: Map<String, EconomyInterval> = log.intervals
                    .filter { it.endRefuelId != null }
                    .associateBy { requireNotNull(it.endRefuelId) }
                val open = log.intervals.lastOrNull { it.status == EconomyStatus.OPEN }
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (open != null) item(key = "open") { EconomyCard(open, title = Texts.LOG_CURRENT) }
                    items(log.refuels, key = { it.id }) { refuel ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RefuelRow(
                                refuel = refuel,
                                zone = zone,
                                busy = state.busyId != null,
                                onAmend = {
                                    controller.amendDismissed()
                                    amendingId = refuel.id
                                },
                            )
                            closedBy[refuel.id]?.let { EconomyCard(it, title = null) }
                        }
                    }
                }
            }
        }
    }
    val amending = amendingId?.let { id -> state.log.refuels.firstOrNull { it.id == id } }
    if (amending != null) {
        AmendDialog(
            refuel = amending,
            busy = state.busyId == amending.id,
            error = state.amendError,
            onSave = { liters, total, full -> controller.amendLiters(amending.id, liters, total, full) },
            onDismiss = {
                controller.amendDismissed()
                amendingId = null
            },
        )
    }
    // The write went through: the dialog closes, the fresh log shows the correction.
    LaunchedEffect(state.amendedId) {
        if (state.amendedId != null && state.amendedId == amendingId) {
            amendingId = null
            controller.amendDismissed()
        }
    }
}

/**
 * Correction of a confirmed refuel with the receipt (docs/SPEC.md §15.3). The liters
 * field starts empty on purpose: only a number the user typed becomes "z paragonu", so a
 * sensor estimate can never be passed off as a receipt. "Do pełna" needs liters.
 */
@Composable
private fun AmendDialog(
    refuel: Refuel,
    busy: Boolean,
    error: String?,
    onSave: (liters: String, total: String, filledToFull: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var litersText by rememberSaveable(refuel.id) { mutableStateOf("") }
    var totalText by rememberSaveable(refuel.id) { mutableStateOf("") }
    var full by rememberSaveable(refuel.id) { mutableStateOf(refuel.filledToFull == true) }
    val litersGiven = parseRefuelAmount(litersText) is RefuelAmount.Given
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(Texts.AMEND_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Texts.AMEND_BODY, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = litersText,
                    onValueChange = { litersText = it },
                    label = { Text(Texts.AMEND_FIELD_LITERS) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = totalText,
                    onValueChange = { totalText = it },
                    label = { Text(Texts.AMEND_FIELD_TOTAL) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Texts.REFUEL_FILLED_FULL, modifier = Modifier.weight(1f))
                    // Shows the stored mark even before liters are typed; only liters make it editable.
                    Switch(checked = full, onCheckedChange = { full = it }, enabled = litersGiven && !busy)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(litersText, totalText, full && litersGiven) }, enabled = !busy) {
                Text(Texts.AMEND_SAVE)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(Texts.AMEND_CANCEL) }
        },
    )
}

@Composable
private fun Centered(innerPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun RefuelRow(refuel: Refuel, zone: ZoneId, busy: Boolean, onAmend: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            RefuelFormatting.time(refuel.ts, zone) + Texts.SEPARATOR +
                RefuelFormatting.levelJump(refuel.levelBefore, refuel.levelAfter),
            style = MaterialTheme.typography.titleSmall,
        )
        RefuelLogFormatting.litersLine(refuel)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val money = listOfNotNull(
            refuel.pricePerLiter?.let { TripsFormatting.pricePerLiter(it) },
            refuel.totalCost?.let { TripsFormatting.money(it) },
        )
        if (money.isNotEmpty()) Text(money.joinToString(Texts.SEPARATOR), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onAmend, enabled = !busy) { Text(Texts.AMEND_BUTTON) }
    }
}

@Composable
private fun EconomyCard(interval: EconomyInterval, title: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(RefuelLogFormatting.economyLine(interval), style = MaterialTheme.typography.bodyMedium)
            Text(RefuelLogFormatting.detailLine(interval), style = MaterialTheme.typography.bodySmall)
        }
    }
}
