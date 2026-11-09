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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.VehicleHealth
import pl.lk.toyotacompanion.core.domain.VehicleLocation
import pl.lk.toyotacompanion.core.domain.VehicleStatus

/**
 * The three read-only Toyota screens (PRD F41-F43, docs/SPEC.md §42-§44). Each reads once on every opening
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

@Composable
private fun ComponentRow(line: ComponentLine) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(line.name, style = MaterialTheme.typography.bodyMedium)
        Text(line.state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.load() }
    val status = state.data
    RemoteScreen(Texts.VEHICLE_STATUS_TITLE, onBack) {
        RemoteState(state.loading, state.unavailable, state.failed, status != null, Texts.VEHICLE_STATUS_READ_ERROR) {
            controller.load()
        }
        if (status != null) {
            DashboardCard(Texts.VEHICLE_STATUS_TITLE, AppIcons.DirectionsCar) {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    controller: RemoteDataController<VehicleLocation>,
    onBack: () -> Unit,
    onShowMap: (List<MapPoint>) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val state by controller.state.collectAsState()
    // Memory-only (docs/SECURITY.md): read on every opening and forget the position when the screen is left.
    LaunchedEffect(Unit) { controller.load() }
    val location = state.data
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
                location.displayName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                coordinatesText(location)?.let {
                    Text(Texts.LOCATION_COORDS_TITLE, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
                location.acquiredAt?.let { Text(Texts.locationAcquired(TripsFormatting.startDateTime(it, zone))) }
                location.lastUpdate?.let { Text(Texts.locationLastUpdate(TripsFormatting.startDateTime(it, zone))) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleHealthScreen(
    controller: RemoteDataController<VehicleHealth>,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.load() }
    val health = state.data
    RemoteScreen(Texts.HEALTH_TITLE, onBack) {
        RemoteState(state.loading, state.unavailable, state.failed, health != null, Texts.HEALTH_READ_ERROR) {
            controller.load()
        }
        if (health != null) {
            DashboardCard(Texts.HEALTH_TITLE, AppIcons.Build) {
                health.warnings?.let { Text(Texts.vehicleWarnings(it)) }
                health.hasOilInfo?.let { Text(if (it) Texts.HEALTH_OIL_YES else Texts.HEALTH_OIL_NO) }
                health.lastUpdate?.let { Text(Texts.healthUpdated(TripsFormatting.startDateTime(it, zone))) }
                Text(Texts.HEALTH_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
