package pl.lk.toyotacompanion.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/**
 * "Podsumowanie roku" (PRD F28, docs/SPEC.md §29): one page per year with the totals and the notable months, and a button
 * that hands the same lines as plain text to the share sheet. No position is on the page or in the text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearSummaryScreen(
    controller: YearSummaryController,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { controller.ensureLoaded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.YEAR_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        val summary = state.summary
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.loadFailed -> Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(Texts.YEAR_READ_ERROR)
            }
            summary == null -> Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(Texts.YEAR_EMPTY)
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (year in state.years) {
                        FilterChip(
                            selected = year == state.selected,
                            onClick = { controller.load(year) },
                            label = { Text("📅 $year") },
                        )
                    }
                }

                DashboardCard(Texts.yearHeading(summary.year), "📅") {
                    val l = summary.lPer100
                    if (l != null) {
                        Headline(
                            TripsFormatting.plain(l, 2),
                            "l/100 km",
                            Texts.yearTotals(summary.trips, TripsFormatting.plain(summary.distanceKm, 0), TripsFormatting.plain(summary.fuelL, 0)),
                        )
                    } else {
                        Headline(
                            TripsFormatting.plain(summary.distanceKm, 0),
                            "km",
                            Texts.statsTrips(summary.trips),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatTile(TripsFormatting.plain(summary.distanceKm, 0) + " km", Texts.STAT_TRIPS, Palette.Blue, Modifier.weight(1f))
                        StatTile(TripsFormatting.plain(summary.fuelL, 0) + " l", Texts.REFUEL_FIELD_LITERS, Palette.Orange, Modifier.weight(1f))
                        val cost = summary.costPln
                        if (cost != null) {
                            StatTile(TripsFormatting.money(cost), Texts.REFUEL_FIELD_TOTAL, Palette.Violet, Modifier.weight(1f))
                        }
                    }

                    val cost = summary.costPln
                    if (cost != null) {
                        Text(
                            Texts.yearCost(TripsFormatting.money(cost), summary.costPer100?.let { c -> TripsFormatting.money(c) }) +
                                if (summary.tripsWithoutCost > 0) Texts.SEPARATOR + Texts.tripsWithoutCost(summary.tripsWithoutCost) else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(Texts.yearNoCost, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("🗓️ " + Texts.yearDays(summary.daysDriven), style = MaterialTheme.typography.bodyMedium)
                }

                val ev = summary.evSharePct
                if (ev != null) {
                    DashboardCard(Texts.STATS_EV_SECTION, "⚡") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RingGauge(ev.toFloat() / 100f, color = Palette.Green) {
                                Text(TripsFormatting.plain(ev, 0) + "%", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(Texts.yearEv(TripsFormatting.plain(ev, 0)), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                val longestKm = summary.longestTripKm
                val longestStart = summary.longestTripStart
                val hasHighlights = (longestKm != null && longestStart != null) ||
                    summary.bestMonth != null ||
                    summary.worstMonth != null ||
                    summary.dearestMonth != null

                if (hasHighlights) {
                    DashboardCard(Texts.STATS_TITLE, "🏆") {
                        if (longestKm != null && longestStart != null) {
                            Text(
                                "🛣️ " + Texts.yearLongest(TripsFormatting.plain(longestKm, 1), RefuelFormatting.time(longestStart, zone)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        // The best and the worst month of the year side by side, l/100 km (§29).
                        val monthBars = listOfNotNull(
                            summary.bestMonth?.let {
                                ChartBar(it.lPer100.toFloat(), ConsumptionFormatting.monthShort(it.month), TripsFormatting.plain(it.lPer100, 2), Palette.Green)
                            },
                            summary.worstMonth?.takeIf { it.month != summary.bestMonth?.month }?.let {
                                ChartBar(it.lPer100.toFloat(), ConsumptionFormatting.monthShort(it.month), TripsFormatting.plain(it.lPer100, 2), Palette.Red)
                            },
                        )
                        if (monthBars.size > 1) BarChart(monthBars)
                        summary.bestMonth?.let {
                            Text(
                                "🟢 " + Texts.yearBest(TripsFormatting.monthLabel(it.month), TripsFormatting.plain(it.lPer100, 2)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.Green,
                            )
                        }
                        summary.worstMonth?.takeIf { it.month != summary.bestMonth?.month }?.let {
                            Text(
                                "🔴 " + Texts.yearWorst(TripsFormatting.monthLabel(it.month), TripsFormatting.plain(it.lPer100, 2)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.Red,
                            )
                        }
                        summary.dearestMonth?.let { dearest ->
                            dearest.costPln?.let { costVal ->
                                Text(
                                    "💰 " + Texts.yearDearest(TripsFormatting.monthLabel(dearest.month), TripsFormatting.money(costVal)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Palette.Violet,
                                )
                                // How much of the money of the whole year the dearest month took (§29).
                                val yearCost = summary.costPln
                                if (yearCost != null && yearCost.signum() > 0) {
                                    MeterRow(
                                        Texts.CONSUMPTION_COSTS_TITLE,
                                        TripsFormatting.money(costVal),
                                        costVal.toFloat() / yearCost.toFloat(),
                                        Palette.Violet,
                                    )
                                }
                            }
                        }
                    }
                }

                val previous = state.previous
                if (previous != null) {
                    DashboardCard(Texts.STATS_YEAR_OVER_YEAR, "📈") {
                        val kmBars = listOf(
                            ChartBar(previous.distanceKm.toFloat(), previous.year.toString(), TripsFormatting.plain(previous.distanceKm, 0), Palette.Series[0]),
                            ChartBar(summary.distanceKm.toFloat(), summary.year.toString(), TripsFormatting.plain(summary.distanceKm, 0), Palette.Blue),
                        )
                        BarChart(kmBars)

                        Text(
                            Texts.yearVersus(
                                previous.year,
                                percentChange(summary.distanceKm, previous.distanceKm),
                                percentChange(summary.costPln, previous.costPln),
                                summary.lPer100?.let { now -> previous.lPer100?.let { before -> TripsFormatting.signed(now.subtract(before), 2) } },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, yearSummaryShareText(summary, state.previous, zone))
                        context.startActivity(Intent.createChooser(send, Texts.YEAR_SHARE))
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text("📤 " + Texts.YEAR_SHARE)
                }

                Text(Texts.YEAR_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
