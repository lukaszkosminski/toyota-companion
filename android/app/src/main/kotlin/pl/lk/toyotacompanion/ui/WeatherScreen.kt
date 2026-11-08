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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import pl.lk.toyotacompanion.core.domain.TemperatureBucket
import pl.lk.toyotacompanion.core.domain.WeatherBucketStats
import pl.lk.toyotacompanion.core.domain.WeatherImpact

/**
 * "Pogoda i spalanie" (PRD F15, docs/SPEC.md §23): trips by the temperature and the rain of their day, and the
 * button that fetches the missing days. The screen shows no position; the only thing sent to the weather service
 * is the start point rounded to about 1 km, and only after a tap.
 *
 * Every split is a card: the buckets are bars in their own colours, the share of the battery is a green
 * meter and the outcome of a fetch is a pill (docs/SPEC.md 10.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(controller: WeatherController, onBack: () -> Unit) {
    // Leaving the screen ends a running fetch: nothing is requested in the background (docs/SPEC.md §23).
    val leave = { controller.cancelFetch(); onBack() }
    BackHandler(onBack = leave)
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) {
        controller.ensureLoaded()
        // Opening the screen also brings the missing days, when the switch is on (docs/SPEC.md §23).
        controller.autoFetch()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.WEATHER_TITLE) },
                navigationIcon = { TextButton(onClick = leave) { Text(Texts.BACK) } },
            )
        },
        // CC BY 4.0 asks for the credit wherever the data is shown, so it stays on screen while scrolling.
        bottomBar = {
            Text(
                Texts.WEATHER_ATTRIBUTION,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        },
    ) { innerPadding ->
        val view = state.view
        when {
            state.loading -> Message(innerPadding) { CircularProgressIndicator() }
            state.loadFailed || view == null -> Message(innerPadding) { Text(Texts.WEATHER_READ_ERROR) }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DashboardCard(Texts.WEATHER_TITLE, AppIcons.PartlyCloudyDay) {
                        Text(
                            Texts.WEATHER_HINT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Switch(checked = state.auto, onCheckedChange = controller::setAuto)
                            Text(Texts.WEATHER_AUTO, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        Button(
                            onClick = controller::fetch,
                            enabled = !state.fetching && view.plannedRequests > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.fetching) Text(Texts.WEATHER_FETCHING) else IconLabel(AppIcons.Download, Texts.WEATHER_FETCH)
                        }
                        Text(
                            Texts.weatherPending(view.plannedRequests, view.requestsLeft, view.cachedDays),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // The outcome of the last tap: a pill, green when it worked and red when it did not.
                        state.lastFetch?.let { result ->
                            val tint = fetchColor(result)
                            Pill(Texts.weatherFetchResult(result), tint.copy(alpha = 0.18f), tint, icon = fetchIcon(result))
                        }
                    }
                }

                item { ImpactCard(Texts.WEATHER_BY_TEMPERATURE, AppIcons.Thermostat, view.temperature) }
                item { ImpactCard(Texts.WEATHER_BY_RAIN, AppIcons.Rainy, view.precipitation) }
                item { MatrixCard(view.matrix) }

                item {
                    IconText(
                        AppIcons.Warning,
                        Texts.WEATHER_CAVEAT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One weather split as a card: a bar per bucket in the colour of that bucket, the exact line of the domain under
 * its badge, and a meter of the share of the battery the bucket allowed (docs/SPEC.md §10.4, §16).
 */
@Composable
private fun ImpactCard(title: String, icon: ImageVector, impact: WeatherImpact) {
    DashboardCard(title, icon) {
        val bars = impact.buckets.mapNotNull { bucket ->
            val litres = bucket.lPer100Km ?: return@mapNotNull null
            ChartBar(litres.toFloat(), bucketEmoji(bucket.bucket), TripsFormatting.plain(litres, 2), bucketColor(bucket.bucket))
        }
        // Two bars is the least that shows a difference; one bucket alone is only a number.
        if (bars.size > 1) BarChart(bars)
        if (impact.buckets.isEmpty()) {
            Text(Texts.WEATHER_NO_DATA)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(impact.buckets.sumOf { it.trips }.toString(), Texts.STAT_TRIPS, Palette.Blue, Modifier.weight(1f))
                StatTile(
                    TripsFormatting.plain(impact.buckets.fold(BigDecimal.ZERO) { sum, bucket -> sum + bucket.distanceKm }, 0),
                    "km",
                    Palette.Violet,
                    Modifier.weight(1f),
                )
            }
        }
        for (bucket in impact.buckets) BucketRow(bucket)
        if (impact.tripsWithoutWeather > 0) {
            Text(
                Texts.weatherWithout(impact.tripsWithoutWeather, impact.totalTrips),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A bucket: its badge and its name, the exact line, the note under it and, when there is one, the meter of the battery. */
@Composable
private fun BucketRow(bucket: WeatherBucketStats) {
    val color = bucketColor(bucket.bucket)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(bucketIcon(bucket.bucket), color)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(Texts.weatherBucketName(bucket.bucket), style = MaterialTheme.typography.titleSmall)
                Text(WeatherFormatting.bucketLine(bucket), style = MaterialTheme.typography.bodyMedium)
            }
            bucket.lPer100Km?.let {
                Text(TripsFormatting.plain(it, 2), style = MaterialTheme.typography.titleMedium, color = readable(color))
            }
        }
        WeatherFormatting.bucketNote(bucket)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The share of the battery explains part of every gap, so it is shown as a meter of its own (§16).
        val ev = WeatherFormatting.evShare(bucket)
        if (ev != null && bucket.distanceKm.signum() > 0) {
            val fraction = bucket.evKm.min(bucket.distanceKm).divide(bucket.distanceKm, 4, RoundingMode.HALF_UP).toFloat()
            MeterRow(Texts.STATS_EV_SECTION, ev, fraction, Palette.Green)
        }
    }
}

/** The speed × temperature table (docs/SPEC.md §28): a band, its bars per bucket of temperature and its exact line. */
@Composable
private fun MatrixCard(rows: List<MatrixRow>) {
    DashboardCard(Texts.MATRIX_TITLE, AppIcons.Route) {
        Text(Texts.MATRIX_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rows.isEmpty()) Text(Texts.MATRIX_EMPTY)
        for (row in rows) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(trafficIcon(row.speed), trafficColor(row.speed))
                    Text(Texts.trafficClassName(row.speed), style = MaterialTheme.typography.titleSmall)
                }
                // A cell below the minimum gets a grey bar: it counts, but it proves nothing (§28).
                val muted = MaterialTheme.colorScheme.outlineVariant
                val bars = TemperatureBucket.entries.mapNotNull { bucket ->
                    val cell = row.cells[bucket] ?: return@mapNotNull null
                    ChartBar(
                        cell.lPer100.toFloat(),
                        bucketEmoji(bucket.name.lowercase()),
                        TripsFormatting.plain(cell.lPer100, 1),
                        if (cell.enough) bucketColor(bucket.name.lowercase()) else muted,
                    )
                }
                if (bars.size > 1) BarChart(bars, height = 64.dp)
                Text(WeatherFormatting.rowText(row), style = MaterialTheme.typography.bodyMedium)
                WeatherFormatting.frostLine(row)?.let {
                    IconText(AppIcons.AcUnit, it, style = MaterialTheme.typography.bodySmall, color = readable(Palette.Red))
                }
            }
        }
    }
}

/** The symbol of a weather bucket, from the same set the list of trips uses (docs/SPEC.md §10.3). */
/** The badge of a weather bucket row; the bars keep [bucketEmoji] as their short text label. */
private fun bucketIcon(bucket: String): ImageVector = when (bucket) {
    "deep_frost" -> AppIcons.SevereCold
    "frost" -> AppIcons.AcUnit
    "cold" -> AppIcons.Foggy
    "mild" -> AppIcons.PartlyCloudyDay
    "warm" -> AppIcons.Sunny
    "dry" -> AppIcons.HumidityLow
    "wet" -> AppIcons.Rainy
    else -> AppIcons.Thermostat
}

private fun bucketEmoji(bucket: String): String = when (bucket) {
    "deep_frost" -> "🥶"
    "frost" -> "❄️"
    "cold" -> "🌫️"
    "mild" -> "🌤️"
    "warm" -> "☀️"
    "dry" -> "🍂"
    "wet" -> "🌧️"
    else -> "🌡️"
}

/** A colour of its own for every bucket, so a bar and the row under it are the same thing. */
private fun bucketColor(bucket: String): Color = when (bucket) {
    "deep_frost" -> Palette.Pink
    "frost" -> Palette.Blue
    "cold" -> Palette.Teal
    "mild" -> Palette.Green
    "warm" -> Palette.Orange
    "dry" -> Palette.Amber
    "wet" -> Palette.Violet
    else -> Palette.Series.first()
}

/** The symbol of a speed band, the same as the kind of drive of a trip (docs/SPEC.md §27). */
private fun trafficIcon(cls: TrafficClass): ImageVector = when (cls) {
    TrafficClass.SLOW -> AppIcons.Traffic
    TrafficClass.CITY -> AppIcons.LocationCity
    TrafficClass.FLOWING -> AppIcons.DirectionsCar
    TrafficClass.HIGHWAY -> AppIcons.Speed
}

/** Slow driving is the bad news and an open road the good one - the colours of "Statystyki". */
private fun trafficColor(cls: TrafficClass): Color = when (cls) {
    TrafficClass.SLOW -> Palette.Red
    TrafficClass.CITY -> Palette.Amber
    TrafficClass.FLOWING -> Palette.Green
    TrafficClass.HIGHWAY -> Palette.Blue
}

/** The mark of the last tap on fetch: a check, a warning, nothing to say or a cross. */
private fun fetchIcon(result: WeatherFetchResult): ImageVector? = when (result) {
    WeatherFetchResult.DONE -> AppIcons.CheckCircle
    WeatherFetchResult.PARTIAL -> AppIcons.Warning
    WeatherFetchResult.NOTHING_TO_FETCH -> null
    else -> AppIcons.Cancel
}

/** The colour of that mark: green when it worked, amber when it worked partly, red when it did not. */
private fun fetchColor(result: WeatherFetchResult): Color = when (result) {
    WeatherFetchResult.DONE -> Palette.Green
    WeatherFetchResult.PARTIAL -> Palette.Amber
    WeatherFetchResult.NOTHING_TO_FETCH -> Palette.Blue
    else -> Palette.Red
}

@Composable
private fun Message(innerPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
