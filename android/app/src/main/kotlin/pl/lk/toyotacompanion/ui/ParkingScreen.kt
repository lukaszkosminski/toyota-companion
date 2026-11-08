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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
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
    onShowMap: (List<MapPoint>, String?) -> Unit,
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
        val known = remember(log, zone) { log?.let { knownPlaces(it, zone) } }
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    DashboardCard(Texts.PARKING_TITLE, AppIcons.LocalParking) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatTile("${log.places.size}", Texts.PARKING_PLACES, Palette.Blue, Modifier.weight(1f))
                            StatTile("${log.stops.size}", Texts.PARKING_RECENT, Palette.Teal, Modifier.weight(1f))
                        }
                        IconText(
                            AppIcons.Lock,
                            Texts.PARKING_PRIVACY,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Stops without coordinates form no place: then the section is left out.
                if (log.places.isNotEmpty()) {
                    item { SectionHeader(AppIcons.LocationOn, Texts.PARKING_PLACES, color = Palette.Blue) }
                    items(log.places, key = { "p-" + it.lat + "," + it.lon }) { place ->
                        val isHome = known?.home?.let { it.lat == place.lat && it.lon == place.lon } == true
                        val isWork = known?.work?.let { it.lat == place.lat && it.lon == place.lon } == true
                        val icon = when {
                            isHome -> AppIcons.Home
                            isWork -> AppIcons.Apartment
                            else -> AppIcons.LocationOn
                        }
                        val badgeColor = when {
                            isHome -> Palette.Green
                            isWork -> Palette.Amber
                            else -> Palette.Blue
                        }
                        // The longest stay sets the scale, so the places are compared with each other (§18).
                        val longestStay = log.places.maxOf { it.total }.toMillis().coerceAtLeast(1L)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconBadge(icon, badgeColor)
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val label = placeLabel(place, known)
                                    Text(label + ParkingFormatting.place(place.lat, place.lon), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        ParkingFormatting.placeLine(place, zone),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    MeterRow(
                                        Texts.parkingStops(place.stops),
                                        ParkingFormatting.duration(place.total),
                                        place.total.toMillis().toFloat() / longestStay.toFloat(),
                                        badgeColor,
                                    )
                                    if (placeMapPoints(place.lat, place.lon).isNotEmpty()) {
                                        TextButton(onClick = { onShowMap(placeMapPoints(place.lat, place.lon), null) }) {
                                            IconLabel(AppIcons.Map, Texts.PARKING_OPEN_MAP)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item { SectionHeader(AppIcons.Schedule, Texts.PARKING_RECENT, color = Palette.Teal) }
                items(log.stops.take(RECENT_STOPS), key = { "s-" + it.from }) { stop ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconBadge(AppIcons.LocalParking, Palette.Amber)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(ParkingFormatting.stopLine(stop, zone), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    ParkingFormatting.place(stop.lat, stop.lon),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    IconText(
                        AppIcons.Info,
                        Texts.parkingSkipped(log.unmatched, log.longGaps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
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

/** `Dom · ` / `Praca · ` before a recognised place (docs/SPEC.md §21), by position, not by object identity. */
private fun placeLabel(place: ParkingPlace, known: KnownPlaces?): String {
    fun same(other: ParkingPlace?) = other != null && other.lat == place.lat && other.lon == place.lon
    return when {
        same(known?.home) -> Texts.PLACE_HOME + Texts.SEPARATOR
        same(known?.work) -> Texts.PLACE_WORK + Texts.SEPARATOR
        else -> ""
    }
}
