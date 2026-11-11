package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import pl.lk.toyotacompanion.core.domain.TyreSet
import pl.lk.toyotacompanion.core.domain.TyreThreshold

/**
 * The tyre card at the top of "Wydatki" (PRD F56, docs/SPEC.md §56): the set on now with its mileage and months, the history of the fittings, the
 * trips before the first fitting and the reminders due. Two full-width buttons, so nothing is squeezed on a 360 dp screen.
 */
@Composable
fun TyreCard(state: TyreUiState, today: LocalDate, onChange: () -> Unit, onThresholds: () -> Unit, onRemove: () -> Unit) {
    DashboardCard(Texts.TYRE_TITLE, AppIcons.DirectionsCar) {
        Text(Texts.TYRE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val usage = state.usage
        when (tyreCardMode(state)) {
            TyreCardMode.LOADING -> Text(Texts.LOADING, style = MaterialTheme.typography.bodyMedium)
            TyreCardMode.INVITE -> Text(Texts.TYRE_EMPTY, style = MaterialTheme.typography.bodyMedium)
            TyreCardMode.SETS -> if (usage != null) {
            usage.sets.lastOrNull()?.takeIf { it.until == null }?.let { current ->
                Text(Texts.TYRE_ON_NOW_TITLE, style = MaterialTheme.typography.titleSmall)
                Text(TyreFormatting.changeLine(current, today), style = MaterialTheme.typography.bodyMedium)
            }
            if (usage.sets.size > 1) {
                Text(Texts.TYRE_HISTORY_TITLE, style = MaterialTheme.typography.titleSmall)
                usage.sets.asReversed().forEach { Text(TyreFormatting.changeLine(it, today), style = MaterialTheme.typography.bodySmall) }
            }
            if (usage.beforeFirstTrips > 0) {
                Text(TyreFormatting.beforeFirst(usage.beforeFirstKm, usage.beforeFirstTrips), style = MaterialTheme.typography.bodySmall)
            }
            state.due.forEach { reminder ->
                Text(TyreFormatting.reminderLine(reminder), style = MaterialTheme.typography.bodyMedium, color = readable(Palette.Amber))
            }
            }
        }
        OutlinedButton(onClick = onChange, modifier = Modifier.fillMaxWidth()) {
            Text(if (tyreCardMode(state) == TyreCardMode.INVITE) Texts.TYRE_FIRST_BUTTON else Texts.TYRE_CHANGE_BUTTON)
        }
        OutlinedButton(onClick = onThresholds, modifier = Modifier.fillMaxWidth()) { Text(Texts.TYRE_THRESHOLDS_BUTTON) }
        if (state.changes.isNotEmpty()) {
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text(Texts.TYRE_REMOVE_BUTTON) }
        }
    }
}

/** Saves a fitting; returns `null` when saved, otherwise the reason the form shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TyreChangeDialog(
    initialSet: TyreSet,
    onSave: (LocalDate, TyreSet, String) -> TyreFormError?,
    onDismiss: () -> Unit,
) {
    var set by rememberSaveable { mutableStateOf(initialSet) }
    var odometer by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<TyreFormError?>(null) }
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Texts.TYRE_CHANGE_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in TyreSet.entries) {
                        FilterChip(selected = s == set, onClick = { set = s }, label = { Text(Texts.tyreSetName(s)) })
                    }
                }
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(Texts.EXPENSE_DATE + ": " + TyreFormatting.day(LocalDate.ofEpochDay(epochDay)))
                }
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it; error = null },
                    label = { Text(Texts.TYRE_ODOMETER_FIELD) },
                    isError = error == TyreFormError.ODOMETER,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = error?.let { e -> { Text(errorText(e)) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = onSave(LocalDate.ofEpochDay(epochDay), set, odometer)
                if (result == null) onDismiss() else error = result
            }) { Text(Texts.EXPENSE_SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Texts.EXPENSE_CANCEL) } },
    )

    if (pickingDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(epochDay).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { epochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    pickingDate = false
                }) { Text(Texts.EXPENSE_SAVE) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(Texts.EXPENSE_CANCEL) } },
        ) { DatePicker(state = pickerState) }
    }
}

/** The reminder thresholds of both sets; a blank field means no reminder. Returns `null` when saved. */
@Composable
fun TyreThresholdDialog(
    current: Map<TyreSet, TyreThreshold>,
    onSave: (Map<TyreSet, Pair<String, String>>) -> TyreFormError?,
    onDismiss: () -> Unit,
) {
    var summaryMonths by rememberSaveable { mutableStateOf(current[TyreSet.SUMMER]?.months?.toString().orEmpty()) }
    var summaryKm by rememberSaveable { mutableStateOf(current[TyreSet.SUMMER]?.km?.toString().orEmpty()) }
    var winterMonths by rememberSaveable { mutableStateOf(current[TyreSet.WINTER]?.months?.toString().orEmpty()) }
    var winterKm by rememberSaveable { mutableStateOf(current[TyreSet.WINTER]?.km?.toString().orEmpty()) }
    var error by remember { mutableStateOf<TyreFormError?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Texts.TYRE_THRESHOLDS_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Texts.TYRE_THRESHOLDS_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((set, months, km, onMonths, onKm) in listOf(
                    Quad(TyreSet.SUMMER, summaryMonths, summaryKm, { summaryMonths = it; error = null }, { summaryKm = it; error = null }),
                    Quad(TyreSet.WINTER, winterMonths, winterKm, { winterMonths = it; error = null }, { winterKm = it; error = null }),
                )) {
                    Text(Texts.tyreSetName(set), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = months,
                        onValueChange = onMonths,
                        label = { Text(Texts.TYRE_MONTHS_FIELD) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = km,
                        onValueChange = onKm,
                        label = { Text(Texts.TYRE_KM_FIELD) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(errorText(it), style = MaterialTheme.typography.bodySmall, color = readable(Palette.Red)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = onSave(
                    mapOf(
                        TyreSet.SUMMER to (summaryMonths to summaryKm),
                        TyreSet.WINTER to (winterMonths to winterKm),
                    ),
                )
                if (result == null) onDismiss() else error = result
            }) { Text(Texts.EXPENSE_SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Texts.EXPENSE_CANCEL) } },
    )
}

/** The text of a form error. */
fun errorText(error: TyreFormError): String = when (error) {
    TyreFormError.ODOMETER -> Texts.TYRE_ERR_ODOMETER
    TyreFormError.FUTURE -> Texts.TYRE_ERR_FUTURE
    TyreFormError.DUPLICATE -> Texts.TYRE_ERR_DUPLICATE
    TyreFormError.BEFORE_LAST -> Texts.TYRE_ERR_BEFORE_LAST
    TyreFormError.THRESHOLD -> Texts.TYRE_ERR_THRESHOLD
}

/** A row of the threshold form: the set, its two texts and their setters. */
private data class Quad(
    val set: TyreSet,
    val months: String,
    val km: String,
    val onMonths: (String) -> Unit,
    val onKm: (String) -> Unit,
)
