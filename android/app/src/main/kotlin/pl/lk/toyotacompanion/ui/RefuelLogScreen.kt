package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId
import pl.lk.toyotacompanion.receipts.ReceiptException
import pl.lk.toyotacompanion.receipts.ReceiptOwner
import pl.lk.toyotacompanion.receipts.ReceiptRepository
import pl.lk.toyotacompanion.receipts.receiptKey
import pl.lk.toyotacompanion.core.domain.EconomyInterval
import pl.lk.toyotacompanion.core.domain.EconomyStatus
import pl.lk.toyotacompanion.core.domain.Refuel
import pl.lk.toyotacompanion.core.domain.StationSummary
import pl.lk.toyotacompanion.core.domain.normalizeStation
import pl.lk.toyotacompanion.core.domain.stationSuggestions
import pl.lk.toyotacompanion.core.domain.stationSummaries

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
    receipts: ReceiptRepository,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    // The receipt photos (PRD F59): which entries have one, and the one shown in the window.
    val receiptIds by receipts.attached.collectAsState()
    LaunchedEffect(Unit) { receipts.refresh() }
    var viewingRefuel by rememberSaveable { mutableStateOf<String?>(null) }
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
                    if (state.levels.size >= 2) item(key = "levels") {
                        DashboardCard(Texts.LEVEL_CHART_TITLE, AppIcons.LocalGasStation) {
                            val recent = state.levels.takeLast(LEVEL_CHART_MAX_POINTS)
                            val step = (recent.size / LEVEL_CHART_MAX_POINTS.toFloat()).coerceAtLeast(1f)
                            val points = recent.filterIndexed { i, _ -> i == recent.lastIndex || (i % step.toInt().coerceAtLeast(1)) == 0 }
                            FuelLevelChart(
                                points.map { it.ts.toEpochMilli() to it.levelPct },
                                log.refuels.map { it.ts.toEpochMilli() },
                                zone = zone,
                                description = Texts.levelChartDescription(
                                    points.minOf { it.levelPct },
                                    points.maxOf { it.levelPct },
                                    points.last().levelPct,
                                    // The same refuels the chart draws: inside the time span of the line.
                                    log.refuels.count { it.ts >= points.first().ts && it.ts <= points.last().ts },
                                ),
                            )
                            val marked = log.refuels.count { it.ts >= points.first().ts && it.ts <= points.last().ts }
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.size(10.dp).background(Palette.Orange, CircleShape))
                                Text(Texts.levelChartLegend(marked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (open != null) item(key = "open") { EconomyCard(open, title = Texts.LOG_CURRENT) }
                    val stationRows = stationSummaries(log.refuels, log.intervals)
                    if (stationRows.isNotEmpty() || log.refuels.any { normalizeStation(it.station) == null }) {
                        item(key = "stations") {
                            StationCard(
                                stations = stationRows,
                                withoutStation = log.refuels.count { normalizeStation(it.station) == null },
                            )
                        }
                    }
                    items(log.refuels, key = { it.id }) { refuel ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RefuelRow(
                                refuel = refuel,
                                zone = zone,
                                busy = state.busyId != null,
                                hasPhoto = receiptKey(ReceiptOwner.REFUEL, refuel.id) in receiptIds,
                                onShowPhoto = { viewingRefuel = refuel.id },
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
            receipts = receipts,
            busy = state.busyId == amending.id,
            error = state.amendError,
            stationSuggestions = stationSuggestions(state.log.refuels),
            onSave = { liters, total, full, station ->
                controller.amendLiters(amending.id, liters, total, full, station)
            },
            onDismiss = {
                controller.amendDismissed()
                amendingId = null
            },
        )
    }
    viewingRefuel?.let { id ->
        ReceiptViewerDialog(ReceiptOwner.REFUEL, id, receipts) { viewingRefuel = null }
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
    receipts: ReceiptRepository,
    busy: Boolean,
    error: String?,
    stationSuggestions: List<String>,
    onSave: (liters: String, total: String, filledToFull: Boolean, station: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var litersText by rememberSaveable(refuel.id) { mutableStateOf("") }
    var totalText by rememberSaveable(refuel.id) { mutableStateOf("") }
    var full by rememberSaveable(refuel.id) { mutableStateOf(refuel.filledToFull == true) }
    // Prefilled with the stored name; empty means "clear it" (PRD F57).
    var stationText by rememberSaveable(refuel.id) { mutableStateOf(refuel.station ?: "") }
    val litersGiven = parseRefuelAmount(litersText) is RefuelAmount.Given
    // The photo is linked at once, not by Save: it is a separate write (PRD F59), so it can fail on its own.
    val scope = rememberCoroutineScope()
    var photoVersion by rememberSaveable(refuel.id) { mutableIntStateOf(0) }
    val photo by produceState<String?>(null, refuel.id, photoVersion) { value = receipts.nameOf(ReceiptOwner.REFUEL, refuel.id) }
    var photoError by rememberSaveable(refuel.id) { mutableStateOf(false) }
    val pickPhoto = rememberReceiptPicker { uri ->
        photoError = false
        scope.launch {
            try {
                receipts.attach(ReceiptOwner.REFUEL, refuel.id, uri)
                photoVersion++
            } catch (e: ReceiptException) {
                photoError = true
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(Texts.AMEND_TITLE) },
        text = {
            // Scrolls with the keyboard open, like the expense form: a small screen must reach every field.
            Column(
                modifier = Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                StationField(
                    value = stationText,
                    onValueChange = { stationText = it },
                    suggestions = stationSuggestions,
                    enabled = !busy,
                    label = Texts.AMEND_FIELD_STATION,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Texts.REFUEL_FILLED_FULL, modifier = Modifier.weight(1f))
                    // Shows the stored mark even before liters are typed; only liters make it editable.
                    Switch(checked = full, onCheckedChange = { full = it }, enabled = litersGiven && !busy)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val current = photo
                if (current != null) {
                    ReceiptImage(current, receipts, RECEIPT_AMEND_SIDE, Modifier.heightIn(max = 160.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                receipts.remove(ReceiptOwner.REFUEL, refuel.id)
                                photoVersion++
                            }
                        },
                        enabled = !busy,
                    ) { IconLabel(AppIcons.Delete, Texts.RECEIPT_REMOVE) }
                } else {
                    OutlinedButton(onClick = pickPhoto, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        IconLabel(AppIcons.Description, Texts.RECEIPT_ADD)
                    }
                }
                if (photoError) Text(Texts.RECEIPT_IMPORT_ERROR, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(litersText, totalText, full && litersGiven, stationText) },
                enabled = !busy,
            ) {
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
private fun RefuelRow(
    refuel: Refuel,
    zone: ZoneId,
    busy: Boolean,
    hasPhoto: Boolean,
    onShowPhoto: () -> Unit,
    onAmend: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(AppIcons.LocalGasStation, Palette.Orange)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    RefuelFormatting.time(refuel.ts, zone) + Texts.SEPARATOR +
                        RefuelFormatting.levelJump(refuel.levelBefore, refuel.levelAfter),
                    style = MaterialTheme.typography.titleSmall,
                )
                RefuelLogFormatting.litersLine(refuel)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = readable(Palette.Blue)) }
                val money = listOfNotNull(
                    refuel.pricePerLiter?.let { TripsFormatting.pricePerLiter(it) },
                    refuel.totalCost?.let { TripsFormatting.money(it) },
                )
                if (money.isNotEmpty()) IconText(AppIcons.Payments, money.joinToString(Texts.SEPARATOR), color = readable(Palette.Green))
                // The owner's own name; it never goes to shared texts or notifications (PRD F57).
                refuel.station?.let { IconText(AppIcons.LocationOn, it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onAmend, enabled = !busy) { IconLabel(AppIcons.Edit, Texts.AMEND_BUTTON) }
                    if (hasPhoto) {
                        IconButton(onClick = onShowPhoto) { Icon(AppIcons.Description, contentDescription = Texts.RECEIPT_SHOW) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationCard(stations: List<StationSummary>, withoutStation: Int) {
    DashboardCard(Texts.STATION_CARD_TITLE, AppIcons.LocationOn) {
        Text(
            Texts.STATION_CARD_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (station in stations) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(station.name, style = MaterialTheme.typography.titleSmall)
                Text(Texts.stationRefuels(station.refuels), style = MaterialTheme.typography.bodySmall)
                station.avgPricePerLiter?.let {
                    Text(
                        Texts.stationAveragePrice(TripsFormatting.number(it, 2)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // A local value: the smart cast does not work on a property of another module.
                val consumption = station.lPer100Km
                if (consumption != null) {
                    Text(
                        Texts.stationConsumption(
                            TripsFormatting.number(consumption, 2),
                            station.intervals,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        Texts.stationTooFewIntervals(station.intervals),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    RefuelLogFormatting.distance(station.distanceKm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (withoutStation > 0) {
            Text(Texts.stationWithout(withoutStation), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EconomyCard(interval: EconomyInterval, title: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(RefuelLogFormatting.economyLine(interval), style = MaterialTheme.typography.bodyMedium)
            Text(RefuelLogFormatting.detailLine(interval), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The level chart shows the newest readings only (one every 30 minutes would be thousands of points). */
private const val LEVEL_CHART_MAX_POINTS = 400

/** The side of the receipt thumbnail in the correction dialog, in pixels. */
private const val RECEIPT_AMEND_SIDE = 480
