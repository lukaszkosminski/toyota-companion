package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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

/**
 * "Styl jazdy" (PRD F12, F13, docs/SPEC.md §19): Toyota's eco score per month, the routes
 * driven again and again (described without their position) and the trips that burned
 * clearly more than usual on their route. The app does not know why - it only suggests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivingStyleScreen(
    controller: DrivingStyleController,
    onBack: () -> Unit,
    onShowHeat: (List<String>) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.ensureLoaded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.STYLE_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        val view = state.view
        when {
            state.loading -> Message(innerPadding) { CircularProgressIndicator() }
            state.loadFailed -> Message(innerPadding) { Text(Texts.STYLE_READ_ERROR) }
            view == null || view.tripCount == 0 -> Message(innerPadding) { Text(Texts.STYLE_EMPTY) }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    DashboardCard(Texts.STYLE_ECO_SECTION, AppIcons.Eco) {
                        if (view.months.isEmpty()) {
                            Text(Texts.STYLE_ECO_NONE)
                        } else {
                            val months = view.months.asReversed()
                            BarChart(
                                months.map {
                                    val score = it.score.toFloat()
                                    ChartBar(score, ConsumptionFormatting.monthShort(it.month), DrivingStyleFormatting.points(it.score), Palette.score(score / 100f))
                                },
                                max = 100f,
                            )
                            view.months.forEach { month ->
                                val score = month.score.toFloat() / 100f
                                MeterRow(
                                    TripsFormatting.monthLabel(month.month),
                                    Texts.styleEco(DrivingStyleFormatting.points(month.score), month.trips),
                                    score,
                                    Palette.score(score),
                                )
                            }
                        }
                    }
                }

                item {
                    DashboardCard(Texts.STYLE_ROUTES_SECTION, AppIcons.Route) {
                        if (view.routes.isEmpty()) Text(Texts.styleRoutesNone(MIN_ROUTE_TRIPS))
                        view.routes.forEachIndexed { i, route ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IconBadge(AppIcons.LocationOn, Palette.Series[i % Palette.Series.size])
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(Texts.styleRoute(route.number), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        Texts.styleRouteLine(TripsFormatting.number(route.typicalKm, 1) + " km", route.trips, route.usualHour),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val facts = Texts.styleRouteFacts(
                                        route.eco?.let { DrivingStyleFormatting.points(it) },
                                        route.usualLPer100Km?.let { RefuelLogFormatting.consumption(it) },
                                    )
                                    if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    // Under the text, not beside it: next to the text the button left it ~100 px and every word wrapped.
                                    TextButton(onClick = { onShowHeat(route.tripIds) }) { IconLabel(AppIcons.Map, Texts.HEAT_BUTTON) }
                                }
                            }
                        }
                    }
                }

                // The best time to leave of the repeated routes, under them (PRD F53, docs/SPEC.md §53).
                if (view.bestTimes.isNotEmpty()) {
                    item { BestTimeCard(view.bestTimes) }
                }

                item {
                    DashboardCard(Texts.STYLE_ANOMALIES_SECTION, AppIcons.Warning) {
                        Text(Texts.STYLE_ANOMALIES_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (view.anomalies.isEmpty()) IconText(AppIcons.CheckCircle, Texts.STYLE_ANOMALIES_NONE)
                        view.anomalies.forEach { anomaly ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(RefuelFormatting.time(anomaly.trip.record.trip.start, zone), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    Texts.styleAnomaly(
                                        anomaly.routeIndex + 1,
                                        RefuelLogFormatting.consumption(anomaly.lPer100Km),
                                        RefuelLogFormatting.consumption(anomaly.usualLPer100Km),
                                        DrivingStyleFormatting.excess(anomaly.excessPct),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = readable(Palette.Red),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Najlepsza godzina wyjazdu" (PRD F53): per route one conclusion line when two or more cells have data, then one row per cell with data
 * (day and band, median drive time, a bar against the fastest, and the trips and litres per 100 km). No empty cells, no positions.
 */
@Composable
private fun BestTimeCard(routes: List<BestTimeRoute>) {
    DashboardCard(Texts.BEST_TIME_TITLE, AppIcons.Schedule) {
        Text(Texts.BEST_TIME_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        routes.forEach { route ->
            val grid = route.grid
            val fastest = grid.fastest?.medianMinutes ?: grid.cells.minOfOrNull { it.medianMinutes } ?: 0L
            // The bar is the drive time itself, so a longer bar is a longer drive.
            val slowest = grid.cells.maxOfOrNull { it.medianMinutes } ?: 0L
            Text(
                BestTimeFormatting.routeTitle(route.number, route.typicalKm, route.trips),
                style = MaterialTheme.typography.titleSmall,
            )
            BestTimeFormatting.conclusion(grid)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            BestTimeFormatting.shortNote(grid)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            grid.cells.forEach { cell ->
                MeterRow(
                    BestTimeFormatting.cellName(cell),
                    BestTimeFormatting.medianValue(cell),
                    BestTimeFormatting.fraction(cell, slowest),
                    if (cell.medianMinutes == fastest) Palette.Green else Palette.Blue,
                )
                Text(
                    BestTimeFormatting.helper(cell),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Message(innerPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
