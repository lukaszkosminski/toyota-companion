package pl.lk.toyotacompanion.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
                modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (year in state.years) {
                        FilterChip(selected = year == state.selected, onClick = { controller.load(year) }, label = { Text(year.toString()) })
                    }
                }
                Text(Texts.yearHeading(summary.year), style = MaterialTheme.typography.titleLarge)
                for (line in yearSummaryLines(summary, state.previous, zone)) {
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                }
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, yearSummaryShareText(summary, state.previous, zone))
                        context.startActivity(Intent.createChooser(send, Texts.YEAR_SHARE))
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(Texts.YEAR_SHARE) }
                Text(Texts.YEAR_NOTE, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
