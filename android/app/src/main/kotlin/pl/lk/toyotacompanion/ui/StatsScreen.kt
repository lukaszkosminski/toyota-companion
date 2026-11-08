package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import pl.lk.toyotacompanion.core.domain.PeriodStats

/**
 * "Statystyki" (PRD F10, F11, docs/SPEC.md §16-§17): the electric share and the trips with
 * the most electric driving, year over year on a comparable window, then every year and
 * every season. No GPS and no VIN on this screen - places live on "Postoje" only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    controller: StatsController,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.ensureLoaded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.STATS_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        val view = state.view
        when {
            state.loading -> Message(innerPadding) { CircularProgressIndicator() }
            state.loadFailed -> Message(innerPadding) { Text(Texts.STATS_READ_ERROR) }
            view == null || view.years.isEmpty() -> Message(innerPadding) { Text(Texts.STATS_EMPTY) }
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
                    DashboardCard(Texts.STATS_EV_SECTION, AppIcons.Bolt) {
                        val share = view.evShare
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (share != null) {
                                RingGauge(share.toFloat() / 100f, color = Palette.Green) {
                                    Text(TripsFormatting.plain(share, 0) + "%", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            Text(Texts.statsEvTotal(StatsFormatting.share(view.evShare)), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (view.ranking.rows.isNotEmpty()) {
                            Text(Texts.STATS_RANKING, style = MaterialTheme.typography.titleSmall)
                            view.ranking.rows.forEachIndexed { i, row ->
                                val start = view.starts[row.tripId]
                                Column {
                                    if (start != null) Text(RefuelFormatting.time(start, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${i + 1}. " + StatsFormatting.rankingRow(row), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Text(
                            Texts.statsLeftOut(view.ranking.withoutData, view.ranking.tooShort),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val yoy = view.yearOverYear
                val end = view.windowEnd
                if (yoy != null && end != null) {
                    item {
                        DashboardCard(Texts.STATS_YEAR_OVER_YEAR, AppIcons.TrendingUp) {
                            Text(
                                Texts.statsWindow(StatsFormatting.window(end), yoy.first.year, yoy.second.year),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val pair = listOf(yoy.first, yoy.second)
                            val maxKm = pair.maxOf { it.distanceKm.toFloat() }
                            pair.forEachIndexed { i, p ->
                                MeterRow(p.year.toString(), TripsFormatting.plain(p.distanceKm, 0) + " km", p.distanceKm.toFloat() / maxKm.coerceAtLeast(1f), Palette.Series[i])
                            }
                            PeriodRow(yoy.first)
                            PeriodRow(yoy.second)
                        }
                    }
                }

                if (view.tripTypes.isNotEmpty()) {
                    item {
                        DashboardCard(Texts.TRIP_TYPES_SECTION, AppIcons.Explore) {
                            Text(
                                if (view.placesKnown) Texts.TRIP_TYPES_HINT else Texts.TRIP_TYPES_NO_PLACES,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val maxKm = view.tripTypes.maxOf { it.distanceKm.toFloat() }.coerceAtLeast(1f)
                            view.tripTypes.forEachIndexed { i, summary ->
                                MeterRow(
                                    Texts.tripTypeName(summary.type),
                                    TripsFormatting.plain(summary.distanceKm, 1) + " km",
                                    summary.distanceKm.toFloat() / maxKm,
                                    Palette.Series[i % Palette.Series.size],
                                )
                                Text(
                                    listOf(
                                        Texts.statsTrips(summary.trips),
                                        summary.costPln?.let { TripsFormatting.money(it) } ?: Texts.NO_PRICE,
                                    ).joinToString(Texts.SEPARATOR),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (view.evWeeks.isNotEmpty()) {
                    item {
                        DashboardCard(Texts.EV_WEEKS_SECTION, AppIcons.BatteryChargingFull) {
                            EvWeeksBars(view.evWeeks)
                            Text(Texts.EV_WEEKS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            view.evWeeks.asReversed().forEach { week ->
                                Text(EvWeeksFormatting.line(week), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                if (view.traffic.isNotEmpty()) {
                    item {
                        DashboardCard(Texts.TRAFFIC_SECTION, AppIcons.Traffic) {
                            Text(Texts.TRAFFIC_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (view.trafficLeftOut > 0) {
                                Text(Texts.statsLeftOutTrips(view.trafficLeftOut, view.tripTotal), style = MaterialTheme.typography.bodySmall)
                            }
                            val maxL = view.traffic.maxOf { it.litresPer100.toFloat() }.coerceAtLeast(0.1f)
                            view.traffic.forEach { row ->
                                val color = when (row.trafficClass) {
                                    TrafficClass.SLOW -> Palette.Red
                                    TrafficClass.CITY -> Palette.Amber
                                    TrafficClass.FLOWING -> Palette.Green
                                    TrafficClass.HIGHWAY -> Palette.Blue
                                }
                                MeterRow(
                                    Texts.trafficClassName(row.trafficClass),
                                    TripsFormatting.plain(row.litresPer100, 2) + " l/100 km",
                                    row.litresPer100.toFloat() / maxL,
                                    color,
                                )
                                Text(
                                    listOfNotNull(
                                        Texts.statsTrips(row.trips),
                                        TripsFormatting.plain(row.distanceKm, 1) + " km",
                                        row.costPer100?.let { Texts.trafficCost(TripsFormatting.money(it)) } ?: Texts.NO_PRICE,
                                        row.evSharePct?.let { Texts.weatherEv(TripsFormatting.plain(it, 0) + " %") },
                                    ).joinToString(Texts.SEPARATOR),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            view.trafficSurcharge?.let { extra ->
                                Text(
                                    Texts.trafficSurcharge(
                                        TripsFormatting.signed(extra.litresPer100, 2),
                                        extra.costPer100?.let { TripsFormatting.signedMoney(it) },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                item {
                    DashboardCard(Texts.STATS_YEARS, AppIcons.CalendarMonth) {
                        val years = view.years
                        BarChart(
                            years.map { ChartBar(it.distanceKm.toFloat(), it.year.toString(), TripsFormatting.plain(it.distanceKm, 0)) },
                            color = Palette.Blue,
                        )
                        years.asReversed().forEach { PeriodRow(it) }
                    }
                }

                item {
                    DashboardCard(Texts.STATS_SEASONS, AppIcons.Thermostat) {
                        view.seasons.asReversed().forEach { PeriodRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodRow(stats: PeriodStats) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(StatsFormatting.periodLabel(stats), style = MaterialTheme.typography.titleSmall)
        Text(StatsFormatting.periodLine(stats), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Message(innerPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** One bar per week: the share of kilometres on the battery, 0-100 % on a fixed axis, the percent above and the Monday below. */
@Composable
private fun EvWeeksBars(weeks: List<EvWeek>) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (week in weeks) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(week.evSharePct.toPlainString(), style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(72.dp * (week.evSharePct.toFloat() / 100f).coerceIn(0.03f, 1f))
                        .background(Palette.score(week.evSharePct.toFloat() / 100f), androidx.compose.foundation.shape.RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                )
                Text(EvWeeksFormatting.weekLabel(week), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
