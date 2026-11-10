package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.lk.toyotacompanion.core.prices.PriceAge
import pl.lk.toyotacompanion.core.prices.StationPrice

/**
 * Where the price comes from (docs/SPEC.md §25): by hand (the field is simply typed) or from one of the owner's stations
 * on trojmiasto.pl. Choosing a station fetches the page once (reused for a few minutes) and puts the 95 price in the field
 * through [onPrice]; the field stays editable and nothing is saved until the owner confirms the form. The note says how
 * old the price is, because readers and stations enter it themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationPricePicker(controller: StationPriceController, onPrice: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    // Empty = by hand. Saved, so a rotation keeps the choice.
    var chosen by rememberSaveable { mutableStateOf("") }
    // A price is put in the field once per choice and per fetch, never over what the owner typed afterwards.
    var delivered by rememberSaveable { mutableStateOf("") }

    val price: StationPrice? = state.prices[chosen]
    LaunchedEffect(chosen, price?.price95, state.fetchedAt) {
        val value = price?.price95 ?: return@LaunchedEffect
        val key = chosen + "|" + state.fetchedAt
        if (delivered != key) {
            delivered = key
            onPrice(TripsFormatting.plain(value, 2))
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Texts.PRICE_SOURCE, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = chosen.isEmpty(),
                onClick = { chosen = "" },
                label = { Text(Texts.PRICE_MANUAL) },
            )
            for (station in PRICE_STATIONS) {
                FilterChip(
                    selected = chosen == station.id,
                    onClick = {
                        chosen = station.id
                        delivered = ""
                        controller.ensureFresh()
                    },
                    label = { Text(station.name) },
                )
            }
        }
        val station = PRICE_STATIONS.firstOrNull { it.id == chosen }
        if (station != null) {
            Text(
                StationPriceFormatting.note(station, state, price),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Polish note under the chips (docs/SPEC.md §25). Pure, independent of `Locale`. */
object StationPriceFormatting {
    fun note(station: PriceStation, state: StationPriceState, price: StationPrice?): String = when {
        state.fetching -> Texts.PRICE_FETCHING
        state.error != null -> Texts.priceFetchError(state.error)
        state.fetchedAt == null -> Texts.PRICE_FETCHING
        price == null -> Texts.priceStationMissing(station.name)
        price.price95 == null -> Texts.priceNone(station.name)
        else -> Texts.priceFound(
            station.name,
            TripsFormatting.number(requireNotNull(price.price95), 2),
            when (price.age) {
                PriceAge.TODAY -> Texts.PRICE_AGE_TODAY
                PriceAge.YESTERDAY -> Texts.PRICE_AGE_YESTERDAY
                else -> Texts.PRICE_AGE_OLDER
            },
            stale = price.age != PriceAge.TODAY,
        )
    }
}
