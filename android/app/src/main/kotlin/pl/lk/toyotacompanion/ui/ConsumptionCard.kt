package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Height of the tallest bar. */
private val BAR_AREA = 96.dp

/**
 * The consumption card of the status screen (docs/SPEC.md §24): the average of the whole archive, one bar per month
 * and the direction of the bars. Hidden without a single usable trip.
 */
@Composable
fun ConsumptionCard(view: ConsumptionView?) {
    if (view?.overall == null) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Texts.CONSUMPTION_TITLE, style = MaterialTheme.typography.titleSmall)
        Text(ConsumptionFormatting.overallLine(view), style = MaterialTheme.typography.bodyMedium)
        if (view.months.isNotEmpty()) {
            val top = view.months.maxOf { it.lPer100 }
            val base = ConsumptionFormatting.axisBase(view.months)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (bar in view.months) {
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            TripsFormatting.plain(bar.lPer100, 1),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(BAR_AREA * ConsumptionFormatting.barShare(bar.lPer100, top, base))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            ConsumptionFormatting.monthShort(bar.month),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            ConsumptionFormatting.trendLine(view)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(Texts.consumptionHint(TripsFormatting.plain(base, 1)), style = MaterialTheme.typography.bodySmall)
        }
    }
}
