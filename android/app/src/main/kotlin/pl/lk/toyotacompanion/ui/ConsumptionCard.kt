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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
fun ConsumptionCard(view: ConsumptionView?, declared: java.math.BigDecimal?, onSetDeclared: (String) -> Boolean) {
    if (view?.overall == null) return
    var editing by remember { mutableStateOf(false) }
    if (editing) DeclaredDialog(onSave = onSetDeclared, onDismiss = { editing = false })
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Texts.CONSUMPTION_TITLE, style = MaterialTheme.typography.titleSmall)
        Text(ConsumptionFormatting.overallLine(view), style = MaterialTheme.typography.bodyMedium)
        declaredComparison(view.overall, declared)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = { editing = true }) {
            Text(if (declared == null) Texts.DECLARED_SET else Texts.DECLARED_EDIT)
        }
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
        if (view.monthCosts.isNotEmpty()) {
            Text(Texts.CONSUMPTION_COSTS_TITLE, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            ConsumptionFormatting.totalCostLine(view)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val topCost = view.monthCosts.maxOf { it.costPln ?: java.math.BigDecimal.ZERO }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (cost in view.monthCosts) {
                    val share = if (topCost.signum() > 0) (cost.costPln ?: java.math.BigDecimal.ZERO).toFloat() / topCost.toFloat() else 0f
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ConsumptionFormatting.costLabel(cost), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(BAR_AREA * share.coerceIn(0.03f, 1f))
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                        Text(
                            ConsumptionFormatting.monthShort(cost.month),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            for (cost in view.monthCosts.asReversed()) {
                Text(ConsumptionFormatting.costLine(cost), style = MaterialTheme.typography.bodySmall)
            }
            Text(Texts.CONSUMPTION_COSTS_HINT, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DeclaredDialog(onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Texts.DECLARED_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Texts.DECLARED_BODY, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; invalid = false },
                    label = { Text(Texts.DECLARED_FIELD) },
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(Texts.DECLARED_INVALID) }) else null,
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(text)) onDismiss() else invalid = true }) { Text(Texts.DECLARED_SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Texts.DECLARED_CANCEL) } },
    )
}
