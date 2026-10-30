package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
 * (docs/SECURITY.md) and every control is a text button, so no icons are needed.
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
    loadTrips: suspend () -> List<StoredTrip>,
    zone: ZoneId = ZoneId.systemDefault(),
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    BackHandler(onBack = onBack)

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
                current.groups.forEach { group ->
                    item(key = "month-" + group.month.year + "-" + group.month.month) {
                        MonthHeader(group)
                    }
                    items(group.trips, key = { it.record.id }) { stored ->
                        TripRow(stored, zone)
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
            if (group.tripsWithoutCost > 0) {
                Text(Texts.tripsWithoutCost(group.tripsWithoutCost), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One row: when the car drove, how far, how much fuel and what it cost. */
@Composable
private fun TripRow(stored: StoredTrip, zone: ZoneId) {
    val trip = stored.record.trip
    val cost = stored.cost
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(TripsFormatting.startDateTime(trip.start, zone), style = MaterialTheme.typography.bodyLarge)
                Text(
                    TripsFormatting.kilometers(trip.distanceKm) + Texts.SEPARATOR + TripsFormatting.liters(trip.fuelL),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (hasNoCost(cost)) {
                        Texts.NO_PRICE
                    } else {
                        TripsFormatting.money(requireNotNull(cost?.costPln))
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                // Price per litre under the cost; "cena domyślna" rides on the same line (§10.3).
                val priceLine = TripsFormatting.priceLine(stored)
                if (priceLine != null) {
                    Text(priceLine, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

