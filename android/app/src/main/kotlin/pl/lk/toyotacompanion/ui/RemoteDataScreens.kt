package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.VehicleHealth
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus

/**
 * The read-only Toyota screens of the vehicle (PRD F41-F43, docs/SPEC.md §42-§44): status with the health card, and the location. Each reads once on every opening
 * (a plain GET of the Toyota cloud cache, so the state is fresh; nothing here polls and nothing calls
 * `refresh_status()` or a command — the car is never woken, docs/PRD.md §6). A 403/404 from Toyota means the
 * route is retired or the car does not support it: its own text, not a retryable error.
 */

/** Shared shell: a title bar with "Wstecz" and one scrollable column of cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** One component; a deviation (open, unlocked) is bold in the readable warning colour of the palette, the rest stays quiet. */
@Composable
private fun ComponentRow(line: ComponentLine) {
    val warning = readable(Palette.Amber)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            line.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (line.deviation) FontWeight.Bold else null,
            color = if (line.deviation) warning else Color.Unspecified,
        )
        Text(
            line.state,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (line.deviation) FontWeight.Bold else null,
            color = if (line.deviation) warning else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Loading, "Toyota does not share this data", a retryable error or the payload — the same order on every screen.
 * A broken retry keeps the last good data: the error and [Texts.REMOTE_STALE] go above it, never instead of it
 * (docs/SPEC.md §47). */
@Composable
internal fun RemoteState(
    loading: Boolean,
    unavailable: Boolean,
    failed: Boolean,
    hasData: Boolean,
    errorText: String,
    emptyText: String = Texts.REMOTE_EMPTY,
    retry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            loading && !hasData -> Text(Texts.LOADING)
            failed -> {
                Text(errorText)
                TextButton(onClick = retry) { Text(Texts.RETRY) }
            }
            unavailable -> Text(Texts.REMOTE_UNAVAILABLE)
            !hasData -> Text(emptyText)
        }
        if (loading && hasData) {
            Text(Texts.LOADING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hasData && (failed || unavailable)) {
            Text(Texts.REMOTE_STALE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleStatusScreen(
    controller: RemoteDataController<VehicleStatus>,
    healthController: RemoteDataController<VehicleHealth>,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val state by controller.state.collectAsState()
    // The health card is its own read with its own error: one failing does not clear the other (docs/SPEC.md §44).
    val healthState by healthController.state.collectAsState()
    LaunchedEffect(Unit) {
        controller.load()
        healthController.load()
    }
    val status = state.data
    val health = healthState.data
    RemoteScreen(Texts.VEHICLE_STATUS_TITLE, onBack) {
        RemoteState(state.loading, state.unavailable, state.failed, status != null, Texts.VEHICLE_STATUS_READ_ERROR) {
            controller.load()
        }
        if (status != null) {
            DashboardCard(Texts.VEHICLE_STATUS_TITLE, AppIcons.DirectionsCar) {
                // One sentence first: all shut and locked, or how many parts need a look (the parts are bold below).
                vehicleSummaryText(status)?.let {
                    val deviations = vehicleSummary(status) is VehicleSummary.Deviations
                    Text(
                        it,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (deviations) readable(Palette.Amber) else readable(Palette.Green),
                    )
                }
                status.lastUpdate?.let { Text(Texts.vehicleUpdated(TripsFormatting.startDateTime(it, zone))) }
                status.warnings?.let { Text(Texts.vehicleWarnings(it)) }
                Text(Texts.VEHICLE_STATUS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val doors = doorLines(status)
            if (doors.isNotEmpty()) {
                DashboardCard(Texts.VEHICLE_DOORS_TITLE, AppIcons.DirectionsCar) {
                    doors.forEach { ComponentRow(it) }
                }
            }
            val windows = windowLines(status)
            if (windows.isNotEmpty()) {
                DashboardCard(Texts.VEHICLE_WINDOWS_TITLE, AppIcons.AcUnit) {
                    windows.forEach { ComponentRow(it) }
                }
            }
        }
        // Vehicle health (PRD F43, docs/SPEC.md §44) is a card of this screen with its own state and error text.
        DashboardCard(Texts.HEALTH_TITLE, AppIcons.Build) {
            RemoteState(healthState.loading, healthState.unavailable, healthState.failed, health != null, Texts.HEALTH_READ_ERROR) {
                healthController.load()
            }
            if (health != null) {
                health.warnings?.let { Text(Texts.vehicleWarnings(it)) }
                health.hasOilInfo?.let { Text(if (it) Texts.HEALTH_OIL_YES else Texts.HEALTH_OIL_NO) }
                health.lastUpdate?.let { Text(Texts.healthUpdated(TripsFormatting.startDateTime(it, zone))) }
                Text(Texts.HEALTH_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    controller: RemoteDataController<VehicleLocation>,
    onBack: () -> Unit,
    onShowMap: (List<MapPoint>) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
    /** Home and work from the parking diary of the archive; compared with the position in memory only (ADR-022). */
    findPlaces: suspend () -> KnownPlaces? = { null },
) {
    val state by controller.state.collectAsState()
    // Memory-only (docs/SECURITY.md): read on every opening and forget the position when the screen is left.
    LaunchedEffect(Unit) { controller.load() }
    val location = state.data
    // Plain `remember`: the places are only a name for the position and are forgotten with it.
    var known by remember { mutableStateOf<KnownPlaces?>(null) }
    LaunchedEffect(location?.latitude, location?.longitude) {
        known = if (location?.latitude == null || location.longitude == null) {
            null
        } else {
            try {
                findPlaces()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    val leave = {
        controller.reset()
        onBack()
    }
    RemoteScreen(Texts.LOCATION_TITLE, leave) {
        RemoteState(state.loading, state.unavailable, state.failed, location != null, Texts.LOCATION_READ_ERROR) {
            controller.load()
        }
        if (location != null) {
            DashboardCard(Texts.LOCATION_TITLE, AppIcons.LocationOn) {
                locationNameText(location.displayName)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                // The place from the parking diary stands above the coordinates, so "Dom" is read before the numbers.
                knownPlaceNear(location.latitude, location.longitude, known)?.let {
                    Text(Texts.LOCATION_PLACE_TITLE, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
                coordinatesText(location)?.let {
                    Text(Texts.LOCATION_COORDS_TITLE, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
                locationTimeLines(location, zone).forEach { Text(it) }
                // The map opens only after a tap, with this one exact point (the tile server sees the area; docs/SECURITY.md).
                val latitude = location.latitude
                val longitude = location.longitude
                if (latitude != null && longitude != null) {
                    TextButton(onClick = { onShowMap(listOf(MapPoint(Texts.MAP_PLACE, latitude, longitude))) }) {
                        Text(Texts.SHOW_ON_MAP)
                    }
                }
                Text(Texts.LOCATION_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
