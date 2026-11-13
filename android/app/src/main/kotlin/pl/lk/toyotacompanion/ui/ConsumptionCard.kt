package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** How many months the money card lists before the rest is folded. */
const val COST_LINES_FOLDED = 3

/** Height of the tallest bar. */
private val BAR_AREA = 96.dp

/**
 * The consumption card of the status screen (docs/SPEC.md §24, §10.4): the average of the whole archive as a headline, the
 * comparison with the declared value, the direction of the bars as a coloured pill, and one bar per month. Hidden without
 * a single usable trip.
 */
@Composable
fun ConsumptionCard(
    view: ConsumptionView?,
    declared: java.math.BigDecimal?,
    onSetDeclared: (String) -> Boolean,
) {
    if (view?.overall == null) return
    var editing by remember { mutableStateOf(false) }
    if (editing) DeclaredDialog(onSave = onSetDeclared, onDismiss = { editing = false })
    DashboardCard(Texts.CONSUMPTION_TITLE, AppIcons.LocalGasStation) {
        Headline(
            TripsFormatting.number(view.overall, 2),
            "l/100 km",
            Texts.consumptionCaption(TripsFormatting.kilometers(view.overallKm)),
        )
        ConsumptionFormatting.trendPillText(view)?.let { line ->
            val scheme = MaterialTheme.colorScheme
            // A rising average is the bad news of this card, a falling one the good news.
            val (container, content) = when (view.trend) {
                ConsumptionTrend.RISING -> Palette.Red.copy(alpha = 0.18f) to Palette.Red
                ConsumptionTrend.FALLING -> Palette.Green.copy(alpha = 0.18f) to Palette.Green
                else -> scheme.surfaceVariant to scheme.onSurfaceVariant
            }
            Pill(
                line,
                container,
                content,
                icon = when (view.trend) {
                    ConsumptionTrend.RISING -> AppIcons.TrendingUp
                    ConsumptionTrend.FALLING -> AppIcons.TrendingDown
                    else -> AppIcons.TrendingFlat
                },
            )
        }
        declaredComparison(view.overall, declared)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = { editing = true }) {
            Text(if (declared == null) Texts.DECLARED_SET else Texts.DECLARED_EDIT)
        }
        if (view.months.isNotEmpty()) {
            val top = view.months.maxOf { it.lPer100 }
            val base = ConsumptionFormatting.axisBase(view.months)
            Bars(
                bars = view.months.map { BarData(TripsFormatting.number(it.lPer100, 1), ConsumptionFormatting.monthShort(it.month), ConsumptionFormatting.barShare(it.lPer100, top, base), consumptionColor(it.lPer100.toFloat(), view.overall.toFloat())) },
                color = MaterialTheme.colorScheme.primary,
            )
            Text(Texts.consumptionHint(TripsFormatting.number(base, 1)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The monthly money card (docs/SPEC.md §24): the total of the whole archive as a headline, one bar per month from zero and
 * the exact amounts below, newest first. Hidden without a priced month.
 */
@Composable
fun CostsCard(view: ConsumptionView?) {
    if (view == null || view.monthCosts.isEmpty()) return
    DashboardCard(Texts.CONSUMPTION_COSTS_TITLE, AppIcons.Payments) {
        // The headline answers the title: money per month. The total of the whole archive is a line under it, with its period.
        view.averageMonthCost?.let {
            Headline(
                TripsFormatting.money(it).removeSuffix(" zł"),
                Lang.pick("zł / miesiąc", "zł / month"),
                Texts.costsAverageCaption(view.averageMonths),
            )
        }
        ConsumptionFormatting.totalCostLine(view)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val topCost = view.monthCosts.maxOf { it.costPln ?: java.math.BigDecimal.ZERO }
        Bars(
            bars = view.monthCosts.map {
                BarData(ConsumptionFormatting.costLabel(it), ConsumptionFormatting.monthShort(it.month), costBarShare(it.costPln, topCost), Palette.Teal)
            },
            color = Palette.Teal,
        )
        // The three newest months in detail; the rest folds away so the card stays short (docs/SPEC.md §10.4).
        var expanded by rememberSaveable { mutableStateOf(false) }
        val newestFirst = view.monthCosts.asReversed()
        for (cost in if (expanded) newestFirst else newestFirst.take(COST_LINES_FOLDED)) {
            Text(ConsumptionFormatting.costLine(cost), style = MaterialTheme.typography.bodySmall)
        }
        if (newestFirst.size > COST_LINES_FOLDED) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) Texts.COSTS_FOLD else Texts.costsUnfold(newestFirst.size))
            }
        }
        Text(Texts.CONSUMPTION_COSTS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Height of a month's cost bar as a share of the dearest month: none for a month without a priced trip or with 0 zł,
 * at least 3 % for any positive amount so a cheap month is still visible next to a dear one.
 */
internal fun costBarShare(cost: java.math.BigDecimal?, top: java.math.BigDecimal): Float {
    if (cost == null || cost.signum() <= 0 || top.signum() <= 0) return 0f
    return (cost.toFloat() / top.toFloat()).coerceIn(MIN_COST_BAR, 1f)
}

private const val MIN_COST_BAR = 0.03f

/** One bar: the label above it, its height as a share of the tallest (0-1) and the month under it. */
private data class BarData(val label: String, val caption: String, val share: Float, val color: androidx.compose.ui.graphics.Color? = null)

/** A month at or under the archive average is green, up to 8 % above amber, above that red. */
private fun consumptionColor(month: Float, overall: Float): androidx.compose.ui.graphics.Color = when {
    month <= overall -> Palette.Green
    month <= overall * 1.08f -> Palette.Amber
    else -> Palette.Red
}

@Composable
private fun Bars(bars: List<BarData>, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (bar in bars) {
            // Amount and month are read together by a screen reader.
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(bar.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(BAR_AREA * bar.share)
                        .background(bar.color ?: color, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                )
                Text(bar.caption, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
            }
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
            // The field and the keyboard: the dialog scrolls, so Save stays reachable on a small screen (C41).
            Column(
                modifier = Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
