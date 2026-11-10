package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.TrafficClass
import pl.lk.toyotacompanion.core.domain.PeriodStats
import pl.lk.toyotacompanion.core.domain.COLD_GAP_HOURS
import pl.lk.toyotacompanion.core.domain.PlannedTripResult
import pl.lk.toyotacompanion.core.domain.plannedTripCost
import pl.lk.toyotacompanion.core.domain.LengthBand
import pl.lk.toyotacompanion.core.domain.ShortTripSummary

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
                // The trip cost calculator on top (PRD F52, docs/SPEC.md §52): the newest tool, before the figures of the archive.
                item { PlannedTripCard(view) }

                item {
                    DashboardCard(Texts.STATS_EV_SECTION, AppIcons.Bolt) {
                        val share = view.evShare
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (share != null) {
                                RingGauge(share.toFloat() / 100f, color = Palette.Green) {
                                    Text(TripsFormatting.number(share, 0) + "%", style = MaterialTheme.typography.titleMedium)
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
                                MeterRow(p.year.toString(), TripsFormatting.number(p.distanceKm, 0) + " km", p.distanceKm.toFloat() / maxKm.coerceAtLeast(1f), Palette.Series[i])
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
                                    TripsFormatting.number(summary.distanceKm, 1) + " km",
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
                            BarChart(
                                view.evWeeks.map {
                                    ChartBar(it.evSharePct.toFloat(), EvWeeksFormatting.weekLabel(it), it.evSharePct.toPlainString(), Palette.score(it.evSharePct.toFloat() / 100f))
                                },
                                max = 100f,
                                height = 72.dp,
                            )
                            Text(Texts.EV_WEEKS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // One row per week: the text on the left, the share on the right and the bar under them (C32 U16).
                            view.evWeeks.asReversed().forEach { week ->
                                val share = week.evSharePct.toFloat() / 100f
                                MeterRow(EvWeeksFormatting.line(week), EvWeeksFormatting.share(week), share, Palette.score(share))
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
                                    TripsFormatting.number(row.litresPer100, 2) + " l/100 km",
                                    row.litresPer100.toFloat() / maxL,
                                    color,
                                )
                                Text(
                                    listOfNotNull(
                                        Texts.statsTrips(row.trips),
                                        TripsFormatting.number(row.distanceKm, 1) + " km",
                                        row.costPer100?.let { Texts.trafficCost(TripsFormatting.money(it)) } ?: Texts.NO_PRICE,
                                        row.evSharePct?.let { Texts.weatherEv(TripsFormatting.number(it, 0) + " %") },
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

                // Short trips and the cold engine (PRD F50, docs/SPEC.md §50): one meter row per length band with one helper line.
                if (view.shortTrips.bands.isNotEmpty()) {
                    item { ShortTripsCard(view.shortTrips) }
                }

                item {
                    DashboardCard(Texts.STATS_YEARS, AppIcons.CalendarMonth) {
                        val years = view.years
                        BarChart(
                            years.map { ChartBar(it.distanceKm.toFloat(), it.year.toString(), TripsFormatting.number(it.distanceKm, 0)) },
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

/**
 * The trip cost calculator (PRD F52, docs/SPEC.md §52): kilometres, one of four kinds of drive, and the range from the own archive. The
 * kind starts at the one with the most trips; a price comes from the last priced trip or the default price.
 */
@Composable
private fun PlannedTripCard(view: StatsView) {
    var km by rememberSaveable { mutableStateOf("") }
    var band by rememberSaveable { mutableStateOf(view.planDefaultBand ?: TrafficClass.FLOWING) }
    val parsed = PlannedTripFormatting.parseKm(km)
    val result = parsed?.let { plannedTripCost(view.planSamples, it, band, view.planPrice) }
    DashboardCard(Texts.PLANNED_TITLE, AppIcons.Route) {
        Text(Texts.PLANNED_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = km,
            onValueChange = { km = it },
            label = { Text(Texts.PLANNED_KM_LABEL) },
            singleLine = true,
            isError = km.isNotBlank() && parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(Texts.PLANNED_KIND_LABEL, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Four buttons on one row, each with one short word, so they fit a 360 dp screen (C32).
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            TrafficClass.entries.forEach { cls ->
                FilterChip(
                    selected = band == cls,
                    onClick = { band = cls },
                    label = { Text(Texts.plannedKind(cls), maxLines = 1, softWrap = false) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        when (val r = result) {
            null -> Unit
            is PlannedTripResult.Range -> {
                Text(PlannedTripFormatting.headline(r.range), style = MaterialTheme.typography.titleMedium)
                Text(PlannedTripFormatting.helper(r.range), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.range.costLow == null) {
                    Text(Texts.PLANNED_NO_PRICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> PlannedTripFormatting.notRange(r)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** The card of PRD F50: summary, a meter row per band, the reference row and, when there is enough, the cold-start block. */
@Composable
private fun ShortTripsCard(summary: ShortTripSummary) {
    DashboardCard(Texts.SHORT_TITLE, AppIcons.Thermostat) {
        Text(
            Texts.shortHint(COLD_GAP_HOURS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ShortTripsFormatting.headline(summary)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        ShortTripsFormatting.excessLine(summary)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        val highest = ShortTripsFormatting.highest(summary)
        summary.bands.forEach { row ->
            MeterRow(
                ShortTripsFormatting.bandName(row.band),
                ShortTripsFormatting.bandValue(row),
                ShortTripsFormatting.fraction(row.lPer100Km, highest),
                shortBandColor(row.band),
            )
            Text(ShortTripsFormatting.bandLine(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        summary.reference?.let { reference ->
            MeterRow(
                ShortTripsFormatting.referenceLabel(),
                reference.lPer100Km?.let { Texts.trafficConsumption(TripsFormatting.number(it, 2)) } ?: Texts.SHORT_TOO_FEW,
                ShortTripsFormatting.fraction(reference.lPer100Km, highest),
                Palette.Green,
            )
        }
        Text(Texts.SHORT_COLD_TITLE, style = MaterialTheme.typography.titleSmall)
        val cold = ShortTripsFormatting.coldLines(summary)
        if (cold.isEmpty()) {
            Text(Texts.SHORT_COLD_NONE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            cold.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Short bands run from red to amber, the longer ones are the calm colours of the reference. */
private fun shortBandColor(band: LengthBand) = when (band) {
    LengthBand.UNDER_2 -> Palette.Red
    LengthBand.FROM_2 -> Palette.Orange
    LengthBand.FROM_5 -> Palette.Amber
    LengthBand.FROM_10 -> Palette.Teal
    LengthBand.FROM_25 -> Palette.Blue
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
