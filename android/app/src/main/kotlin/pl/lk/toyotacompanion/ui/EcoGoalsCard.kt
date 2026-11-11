package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * The "Cel miesiąca" card of the status screen (docs/SPEC.md §40, §10.4), under "Spalanie": the month in progress against the
 * goals and the verdict on the previous month. Hidden without a goal; the way to set the first one is the button in the
 * consumption card. Without figures yet (the trips are still being read) it shows only the button.
 */
@Composable
fun EcoGoalsCard(state: EcoGoalsState, onEdit: () -> Unit) {
    if (!state.goals.any) return
    DashboardCard(Texts.ECO_TITLE, AppIcons.Eco) {
        val progress = state.progress
        if (progress != null) {
            for (line in ecoCurrentLines(progress)) Text(line, style = MaterialTheme.typography.bodyLarge)
            ecoPreviousLine(progress)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Two short lines at most: the streak (from two months) and the records (from three full months with driving).
            ecoStreakLine(state.history)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            ecoRecordLine(state.history)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        TextButton(onClick = onEdit) { Text(Texts.ECO_EDIT) }
    }
}

/** Two fields, one per goal, filled with the current values; an empty field removes its goal, a wrong one keeps the dialog open. */
@Composable
fun EcoGoalsDialog(goals: EcoGoals, onSave: (String, String) -> Boolean, onDismiss: () -> Unit) {
    var consumption by rememberSaveable { mutableStateOf(goals.maxConsumption?.let { TripsFormatting.plain(it, 2) }.orEmpty()) }
    var ev by rememberSaveable { mutableStateOf(goals.minEvSharePct?.toString().orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Texts.ECO_DIALOG_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Texts.ECO_BODY, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = consumption,
                    onValueChange = { consumption = it; invalid = false },
                    label = { Text(Texts.ECO_CONSUMPTION_FIELD) },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = ev,
                    onValueChange = { ev = it; invalid = false },
                    label = { Text(Texts.ECO_EV_FIELD) },
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(Texts.ECO_INVALID) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(consumption, ev)) onDismiss() else invalid = true }) { Text(Texts.ECO_SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Texts.ECO_CANCEL) } },
    )
}
