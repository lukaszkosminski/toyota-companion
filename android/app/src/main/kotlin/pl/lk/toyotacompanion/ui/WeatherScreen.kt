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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import pl.lk.toyotacompanion.core.domain.WeatherBucketStats
import pl.lk.toyotacompanion.core.domain.WeatherImpact

/**
 * "Pogoda i spalanie" (PRD F15, docs/SPEC.md §23): trips by the temperature and the rain of their day, and the
 * button that fetches the missing days. The screen shows no position; the only thing sent to the weather service
 * is the start point rounded to about 1 km, and only after a tap.
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(Texts.WEATHER_HINT, style = MaterialTheme.typography.bodySmall) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = state.auto, onCheckedChange = controller::setAuto)
                        Text(Texts.WEATHER_AUTO, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                item {
                    Button(
                        onClick = controller::fetch,
                        enabled = !state.fetching && view.plannedRequests > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.fetching) Texts.WEATHER_FETCHING else Texts.WEATHER_FETCH) }
                }
                item {
                    Text(
                        Texts.weatherPending(view.plannedRequests, view.requestsLeft, view.cachedDays),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.lastFetch?.let { result ->
                    item { Text(Texts.weatherFetchResult(result), style = MaterialTheme.typography.bodyMedium) }
                }

                impactSection(Texts.WEATHER_BY_TEMPERATURE, view.temperature)
                impactSection(Texts.WEATHER_BY_RAIN, view.precipitation)

                item { HorizontalDivider() }
                item { Text(Texts.MATRIX_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                item { Text(Texts.MATRIX_HINT, style = MaterialTheme.typography.bodySmall) }
                if (view.matrix.isEmpty()) item { Text(Texts.MATRIX_EMPTY) }
                items(view.matrix, key = { "mx-" + it.speed }) { row ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(Texts.trafficClassName(row.speed), style = MaterialTheme.typography.titleSmall)
                        Text(WeatherFormatting.rowText(row), style = MaterialTheme.typography.bodyMedium)
                        WeatherFormatting.frostLine(row)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }

                item { HorizontalDivider() }
                item { Text(Texts.WEATHER_CAVEAT, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.impactSection(title: String, impact: WeatherImpact) {
    item { HorizontalDivider() }
    item { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
    if (impact.buckets.isEmpty()) {
        item { Text(Texts.WEATHER_NO_DATA) }
    }
    items(impact.buckets, key = { title + "-" + it.bucket }) { bucket -> BucketRow(bucket) }
    if (impact.tripsWithoutWeather > 0) {
        item {
            Text(Texts.weatherWithout(impact.tripsWithoutWeather, impact.totalTrips), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BucketRow(bucket: WeatherBucketStats) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(Texts.weatherBucketName(bucket.bucket), style = MaterialTheme.typography.titleSmall)
        Text(
            WeatherFormatting.bucketLine(bucket),
            style = MaterialTheme.typography.bodyMedium,
        )
        WeatherFormatting.bucketNote(bucket)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Message(innerPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
