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

/** How many recent stops the screen lists. */
private const val RECENT_STOPS = 50

/**
 * "Postoje" (PRD F17, docs/SPEC.md §18): the places the car returns to, then the recent
 * stops. This is the only screen with positions — rounded to about 100 m, on screen only,
 * never logged, exported or handed to another app (docs/SECURITY.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParkingScreen(
    controller: ParkingController,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.ensureLoaded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.PARKING_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        val log = state.log
        when {
            state.loading -> Message(innerPadding) { CircularProgressIndicator() }
            state.loadFailed -> Message(innerPadding) { Text(Texts.PARKING_READ_ERROR) }
            log == null || log.stops.isEmpty() -> Message(innerPadding) { Text(Texts.PARKING_EMPTY) }
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(Texts.PARKING_PRIVACY, style = MaterialTheme.typography.bodySmall) }
                // Stops without coordinates form no place: then the section is left out.
                if (log.places.isNotEmpty()) {
                    item { Text(Texts.PARKING_PLACES, style = MaterialTheme.typography.titleMedium) }
                    items(log.places, key = { "p-" + it.lat + "," + it.lon }) { place ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(ParkingFormatting.place(place.lat, place.lon), style = MaterialTheme.typography.titleSmall)
                            Text(ParkingFormatting.placeLine(place, zone), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item { HorizontalDivider() }
                }
                item { Text(Texts.PARKING_RECENT, style = MaterialTheme.typography.titleMedium) }
                items(log.stops.take(RECENT_STOPS), key = { "s-" + it.from }) { stop ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(ParkingFormatting.stopLine(stop, zone), style = MaterialTheme.typography.bodyMedium)
                        Text(ParkingFormatting.place(stop.lat, stop.lon), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text(Texts.parkingSkipped(log.unmatched, log.longGaps), style = MaterialTheme.typography.bodySmall) }
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
