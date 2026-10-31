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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
                item { Header(Texts.STATS_EV_SECTION) }
                item { Text(Texts.statsEvTotal(StatsFormatting.share(view.evShare)), style = MaterialTheme.typography.bodyLarge) }
                if (view.ranking.rows.isNotEmpty()) {
                    item { Text(Texts.STATS_RANKING, style = MaterialTheme.typography.titleSmall) }
                    items(view.ranking.rows, key = { "ev-" + it.tripId }) { row ->
                        val start = view.starts[row.tripId]
                        Column {
                            if (start != null) Text(RefuelFormatting.time(start, zone), style = MaterialTheme.typography.bodySmall)
                            Text(StatsFormatting.rankingRow(row), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    Text(
                        Texts.statsLeftOut(view.ranking.withoutData, view.ranking.tooShort),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                val yoy = view.yearOverYear
                val end = view.windowEnd
                if (yoy != null && end != null) {
                    item { HorizontalDivider() }
                    item { Header(Texts.STATS_YEAR_OVER_YEAR) }
                    item {
                        Text(
                            Texts.statsWindow(StatsFormatting.window(end), yoy.first.year, yoy.second.year),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item { PeriodRow(yoy.first) }
                    item { PeriodRow(yoy.second) }
                }

                item { HorizontalDivider() }
                item { Header(Texts.STATS_YEARS) }
                items(view.years.asReversed(), key = { "y-" + it.year }) { PeriodRow(it) }

                item { HorizontalDivider() }
                item { Header(Texts.STATS_SEASONS) }
                items(view.seasons.asReversed(), key = { "s-" + it.year + it.season }) { PeriodRow(it) }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
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
