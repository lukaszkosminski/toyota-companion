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
import pl.lk.toyotacompanion.core.domain.CO2_KG_PER_LITER_GASOLINE

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
                            label = { Text("$year") },
                        )
                    }
                }

                DashboardCard(Texts.yearHeading(summary.year), AppIcons.CalendarMonth) {
                    val l = summary.lPer100
                    if (l != null) {
                        Headline(
                            TripsFormatting.number(l, 2),
                            "l/100 km",
                            Texts.yearTotals(summary.trips, TripsFormatting.number(summary.distanceKm, 0), TripsFormatting.number(summary.fuelL, 0)),
                        )
                    } else {
                        Headline(
                            TripsFormatting.number(summary.distanceKm, 0),
                            "km",
                            Texts.statsTrips(summary.trips),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatTile(TripsFormatting.number(summary.distanceKm, 0) + " km", Texts.STAT_TRIPS, Palette.Blue, Modifier.weight(1f))
                        StatTile(TripsFormatting.number(summary.fuelL, 0) + " l", Texts.REFUEL_FIELD_LITERS, Palette.Orange, Modifier.weight(1f))
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
                    IconText(AppIcons.Eco, Texts.co2(TripsFormatting.co2(summary.co2Kg)))
                    Text(
                        Texts.co2Hint(TripsFormatting.number(CO2_KG_PER_LITER_GASOLINE, 1)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconText(AppIcons.Today, Texts.yearDays(summary.daysDriven))
                }

                val ev = summary.evSharePct
                if (ev != null) {
                    DashboardCard(Texts.STATS_EV_SECTION, AppIcons.Bolt) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RingGauge(ev.toFloat() / 100f, color = Palette.Green) {
                                Text(TripsFormatting.number(ev, 0) + "%", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(Texts.yearEv(TripsFormatting.number(ev, 0)), style = MaterialTheme.typography.bodyLarge)
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
                    DashboardCard(Texts.STATS_TITLE, AppIcons.EmojiEvents) {
                        if (longestKm != null && longestStart != null) {
                            IconText(
                                AppIcons.Route,
                                Texts.yearLongest(TripsFormatting.number(longestKm, 1), RefuelFormatting.time(longestStart, zone)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        // The best and the worst month of the year side by side, l/100 km (§29).
                        val monthBars = listOfNotNull(
                            summary.bestMonth?.let {
                                ChartBar(it.lPer100.toFloat(), ConsumptionFormatting.monthShort(it.month), TripsFormatting.number(it.lPer100, 2), Palette.Green)
                            },
                            summary.worstMonth?.takeIf { it.month != summary.bestMonth?.month }?.let {
                                ChartBar(it.lPer100.toFloat(), ConsumptionFormatting.monthShort(it.month), TripsFormatting.number(it.lPer100, 2), Palette.Red)
                            },
                        )
                        if (monthBars.size > 1) BarChart(monthBars)
                        summary.bestMonth?.let {
                            IconText(
                                AppIcons.ThumbUp,
                                Texts.yearBest(TripsFormatting.monthLabel(it.month), TripsFormatting.number(it.lPer100, 2)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = readable(Palette.Green),
                            )
                        }
                        summary.worstMonth?.takeIf { it.month != summary.bestMonth?.month }?.let {
                            IconText(
                                AppIcons.ThumbDown,
                                Texts.yearWorst(TripsFormatting.monthLabel(it.month), TripsFormatting.number(it.lPer100, 2)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = readable(Palette.Red),
                            )
                        }
                        summary.dearestMonth?.let { dearest ->
                            dearest.costPln?.let { costVal ->
                                IconText(
                                    AppIcons.Payments,
                                    Texts.yearDearest(TripsFormatting.monthLabel(dearest.month), TripsFormatting.money(costVal)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = readable(Palette.Violet),
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

                // Fuel and everything else (PRD F38, docs/SPEC.md §39); the value loss is a separate estimate (PRD F55, §55).
                val ownershipLines = ownershipLines(state.ownership, state.previousOwnership)
                if (ownershipLines.isNotEmpty()) {
                    DashboardCard(Texts.OWNERSHIP_TITLE, AppIcons.Payments) {
                        val share = state.ownership?.cost?.fuelSharePct
                        if (share != null) MeterRow(Texts.OWNERSHIP_FUEL_SHARE, TripsFormatting.number(share, 0) + " %", share.toFloat() / 100f, Palette.Orange)
                        ownershipLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        val valuationDate = state.ownership?.valuationDate
                        if (state.ownership?.valuationStale == true && valuationDate != null) {
                            Text(
                                Texts.ownershipValuationStale(TripsFormatting.date(valuationDate)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.ownership?.cost?.valueLossPln != null) {
                            Text(Texts.OWNERSHIP_VALUE_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(Texts.OWNERSHIP_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                val previous = state.previous
                if (previous != null) {
                    DashboardCard(Texts.STATS_YEAR_OVER_YEAR, AppIcons.TrendingUp) {
                        val kmBars = listOf(
                            ChartBar(previous.distanceKm.toFloat(), previous.year.toString(), TripsFormatting.number(previous.distanceKm, 0), Palette.Series[0]),
                            ChartBar(summary.distanceKm.toFloat(), summary.year.toString(), TripsFormatting.number(summary.distanceKm, 0), Palette.Blue),
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
                            .putExtra(Intent.EXTRA_TEXT, yearSummaryShareText(summary, state.previous, zone, state.ownership, state.previousOwnership))
                        context.startActivity(Intent.createChooser(send, Texts.YEAR_SHARE))
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    IconLabel(AppIcons.Share, Texts.YEAR_SHARE)
                }

                Text(Texts.YEAR_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
