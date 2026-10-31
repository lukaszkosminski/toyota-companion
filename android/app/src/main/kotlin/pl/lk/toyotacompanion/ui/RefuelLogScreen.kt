package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                            RefuelRow(refuel, zone)
                            closedBy[refuel.id]?.let { EconomyCard(it, title = null) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(innerPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun RefuelRow(refuel: Refuel, zone: ZoneId) {
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
