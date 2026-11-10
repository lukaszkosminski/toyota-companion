package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.core.data.StoredTrip
/**
 * What the trip list read is showing right now (docs/SPEC.md §10.3). Private to
 * the screen: grouping and formatting are plain functions tested without Compose,
 * so the screen needs no state holder of its own.
 */
private sealed interface TripsLoad {
    /** The local read is still running. */
    data object Loading : TripsLoad

    /** The selected vehicle has no trips archived yet. */
    data object Empty : TripsLoad

    data class Ready(val groups: List<MonthGroup>) : TripsLoad

    /** The read failed — said without the exception text (docs/SECURITY.md). */
    data object Failed : TripsLoad
}

/**
 * Archived trips with their cost, grouped by month (docs/SPEC.md §10.3).
 *
 * Read-only: no Toyota call and no `refresh_status()` — the archive is what is on
 * the phone (docs/SPEC.md §9.3). No GPS positions and no VIN are displayed
 * (docs/SECURITY.md).
 *
 * @param onBack leaves the screen, also called by [BackHandler]
 * @param loadTrips reads `SyncEngine.trips()`; injected, so the screen never sees a database
 * @param zone zone the months and the timestamps are computed in
 * @param io dispatcher of the local read
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    onBack: () -> Unit,
    onShowMap: (List<MapPoint>, String?) -> Unit,
    weather: WeatherController,
    backfill: RouteBackfillController,
    loadTrips: suspend () -> List<StoredTrip>,
    zone: ZoneId = ZoneId.systemDefault(),
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    BackHandler(onBack = onBack)

    // The weather of each trip's day (docs/SPEC.md §10.3): read from the cache, and the missing days are fetched on their own
    // (§23); the list picks them up as they arrive.
    val weatherState by weather.state.collectAsState()
    val backfillState by backfill.state.collectAsState()
    LaunchedEffect(Unit) {
        backfill.refresh()
        weather.ensureLoaded()
        weather.autoFetch()
    }
    val days by produceState(emptyMap<String, pl.lk.toyotacompanion.core.weather.WeatherDay>(), weatherState.view?.cachedDays, weatherState.fetching) {
        value = try {
            weather.daysOfTrips()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    val load by produceState<TripsLoad>(initialValue = TripsLoad.Loading, loadTrips, zone, io) {
        value = try {
            val groups = groupTripsByMonth(withContext(io) { loadTrips() }, zone)
            if (groups.isEmpty()) TripsLoad.Empty else TripsLoad.Ready(groups)
        } catch (e: CancellationException) {
            // Leaving the screen cancels the read; that is not an error (docs/SPEC.md §10.3).
            throw e
        } catch (e: Exception) {
            TripsLoad.Failed
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.TRIPS_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        // The price per litre is told where it changes, not on every row (docs/SPEC.md §10.3).
        val priceShown = remember(load) { (load as? TripsLoad.Ready)?.let { tripIdsWithPriceLine(it.groups) }.orEmpty() }
        when (val current = load) {
            TripsLoad.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            TripsLoad.Empty -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(Texts.TRIPS_EMPTY)
            }

            TripsLoad.Failed -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(Texts.TRIPS_READ_ERROR)
            }

            // The Scaffold insets go to contentPadding, the month header keys carry
            // the "month-" prefix so no key collides with a trip id (§10.3).
            is TripsLoad.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
            ) {
                // The condition is read inside the item, so the card appears when the counts arrive.
                item(key = "backfill") {
                    if (backfillState.total > 0 &&
                        (backfillState.stored < backfillState.total || backfillState.running || backfillState.result != null)
                    ) {
                        BackfillCard(backfillState, backfill)
                    }
                }
                current.groups.forEach { group ->
                    item(key = "month-" + group.month.year + "-" + group.month.month) {
                        MonthHeader(group)
                    }
                    items(group.trips, key = { it.record.id }) { stored ->
                        TripRow(stored, zone, days[stored.record.id], onShowMap, stored.record.id in priceShown)
                    }
                }
            }
        }
    }
}

/** Month title, its sums and how many trips of the month have no price. */
@Composable
private fun MonthHeader(group: MonthGroup) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(TripsFormatting.monthLabel(group.month), style = MaterialTheme.typography.titleMedium)
            Text(
                TripsFormatting.kilometers(group.totalKm) +
                    Texts.SEPARATOR + TripsFormatting.liters(group.totalLiters) +
                    Texts.SEPARATOR + (group.totalCost?.let { TripsFormatting.money(it) } ?: Texts.NO_PRICE),
                style = MaterialTheme.typography.bodySmall,
            )
            // On a line of its own: next to the litres and the cost the header would wrap on a narrow screen.
            Text(Texts.co2(TripsFormatting.co2(group.co2Kg)), style = MaterialTheme.typography.bodySmall)
            if (group.tripsWithoutCost > 0) {
                Text(Texts.tripsWithoutCost(group.tripsWithoutCost), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One row: when the car drove, how far, how much fuel and what it cost. */
@Composable
private fun TripRow(stored: StoredTrip, zone: ZoneId, day: pl.lk.toyotacompanion.core.weather.WeatherDay?, onShowMap: (List<MapPoint>, String?) -> Unit, showPrice: Boolean = true) {
    val trip = stored.record.trip
    val cost = stored.cost
    val record = stored.record
    val points = remember(record) { tripMapPoints(record) }
    Card(
        // A trip without any position is not clickable: there is nothing to show.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).let {
            if (points.isNotEmpty()) it.clickable { onShowMap(points, record.id) } else it
        },
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(AppIcons.DirectionsCar, Palette.Blue)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(TripsFormatting.startDateTime(trip.start, zone), style = MaterialTheme.typography.bodyLarge)
                Text(
                    TripsFormatting.kilometers(trip.distanceKm) + Texts.SEPARATOR + TripsFormatting.liters(trip.fuelL),
                    style = MaterialTheme.typography.bodySmall,
                    color = readable(Palette.Blue),
                )
                // Consumption of the trip itself, l/100 km; a very short drive says nothing, so it is left out under 1 km.
                tripConsumption(trip.distanceKm, trip.fuelL)?.let { value ->
                    Text(
                        RefuelLogFormatting.consumption(value),
                        style = MaterialTheme.typography.bodySmall,
                        color = readable(if (value.toFloat() <= 5f) Palette.Green else if (value.toFloat() <= 7f) Palette.Amber else Palette.Red),
                    )
                }
                // Weather of the day and the place the trip started at; nothing until it has been fetched.
                WeatherSymbols.line(day)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                // Kind of drive by average speed (§22): slow, city, flowing, route.
                drivingTypeLine(stored)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                // Motorway and speeding kilometres, when Toyota reported them (§41).
                tripDriveExtrasLine(record)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (hasNoCost(cost)) {
                        Texts.NO_PRICE
                    } else {
                        TripsFormatting.money(requireNotNull(cost?.costPln))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasNoCost(cost)) MaterialTheme.colorScheme.onSurfaceVariant else Palette.Green,
                )
                // Price per litre under the cost; "cena domyślna" rides on the same line (§10.3).
                val priceLine = if (showPrice) TripsFormatting.priceLine(stored) else null
                if (priceLine != null) {
                    Text(priceLine, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Counts, the button that saves the routes of older trips and the result of the last run (docs/SPEC.md §33). */
@Composable
private fun BackfillCard(state: BackfillState, controller: RouteBackfillController) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Texts.backfillCounts(state.stored, state.total), style = MaterialTheme.typography.bodyMedium)
            if (state.running) {
                Text(Texts.backfillRunning(state.fetchedNow), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = controller::stop) { Text(Texts.BACKFILL_STOP) }
            } else {
                state.result?.let { Text(Texts.backfillResult(it, state.fetchedNow), style = MaterialTheme.typography.bodySmall) }
                if (state.stored < state.total) {
                    TextButton(onClick = controller::start) { Text(Texts.BACKFILL_BUTTON) }
                    Text(Texts.BACKFILL_HINT, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Litres per 100 km of one trip; `null` without fuel or under 1 km. */
private fun tripConsumption(distanceKm: Double, fuelL: java.math.BigDecimal): java.math.BigDecimal? {
    if (distanceKm < 1.0 || fuelL.signum() <= 0) return null
    return fuelL.multiply(java.math.BigDecimal(100)).divide(java.math.BigDecimal.valueOf(distanceKm), 2, java.math.RoundingMode.HALF_UP)
}
