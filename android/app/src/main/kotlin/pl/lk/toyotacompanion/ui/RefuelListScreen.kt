package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.Refuel

/**
 * Refuels of the selected vehicle that still wait for a price (docs/SPEC.md §11.3,
 * PRD F2). The state lives in [RefuelController], not here: the write has to
 * outlive this screen.
 *
 * Read only from the local archive — no Toyota call and no `refresh_status()`
 * (docs/SPEC.md §9.3). No VIN and no GPS position on screen (docs/SECURITY.md).
 *
 * @param onBack leaves to the status screen; also called by [BackHandler]
 * @param onEdit opens the price form of one entry
 * @param zone zone the timestamps are shown in
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelListScreen(
    controller: RefuelController,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.REFUELS_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            state.loadFailed -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(Texts.REFUELS_READ_ERROR)
            }

            state.refuels.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(Texts.REFUELS_EMPTY)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
            ) {
                items(state.refuels, key = { it.id }) { refuel ->
                    RefuelRow(
                        refuel = refuel,
                        zone = zone,
                        enabled = state.busyId == null,
                        onEdit = { onEdit(refuel.id) },
                    )
                }
            }
        }
    }
}

/** When the car refueled, how much the level jumped and what the odometer said. */
@Composable
private fun RefuelRow(refuel: Refuel, zone: ZoneId, enabled: Boolean, onEdit: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clickable(enabled = enabled, onClick = onEdit),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(RefuelFormatting.time(refuel.ts, zone), style = MaterialTheme.typography.bodyLarge)
                Text(
                    RefuelFormatting.levelJump(refuel.levelBefore, refuel.levelAfter),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(RefuelFormatting.odometer(refuel.odometerKm), style = MaterialTheme.typography.bodySmall)
        }
    }
}
