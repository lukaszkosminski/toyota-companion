package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/**
 * The "Budżet miesiąca" card of the status screen (PRD F48, docs/SPEC.md §49): one limit for fuel and non-fuel
 * expenses and the month in progress against it. Always visible, so the budget can be set even before there is any
 * cost to show; without a limit it offers "Ustaw budżet", with a limit it shows the figures and "Zmień budżet".
 */
@Composable
fun BudgetCard(state: BudgetState, onEdit: () -> Unit) {
    DashboardCard(Texts.BUDGET_TITLE, AppIcons.Payments) {
        val limit = state.budget.limitPln
        if (limit == null) {
            Text(Texts.BUDGET_HINT, style = MaterialTheme.typography.bodyMedium)
            if (state.failed) {
                Text(Texts.BUDGET_READ_ERROR, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onEdit) { Text(Texts.BUDGET_SET) }
            return@DashboardCard
        }
        val progress = state.progress
        val month = state.month
        if (progress != null && month != null) {
            Headline(
                TripsFormatting.number(progress.spentPln, 2),
                "zł",
                Texts.budgetCaption(
                    TripsFormatting.monthLabel(month),
                    TripsFormatting.number(progress.limitPln, 2),
                    progress.percentUsed,
                ),
            )
            Text(Texts.budgetFuel(TripsFormatting.number(progress.fuelPln, 2)), style = MaterialTheme.typography.bodyMedium)
            Text(Texts.budgetExpenses(TripsFormatting.number(progress.expensesPln, 2)), style = MaterialTheme.typography.bodyMedium)
            if (progress.overBudget) {
                Text(
                    Texts.budgetOver(TripsFormatting.number(progress.remainingPln.abs(), 2)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(Texts.budgetRemaining(TripsFormatting.number(progress.remainingPln, 2)), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.failed) {
                Text(Texts.BUDGET_READ_ERROR, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        } else {
            when {
                state.loading -> Text(Texts.LOADING, style = MaterialTheme.typography.bodyMedium)
                state.failed -> Text(
                    Texts.BUDGET_READ_ERROR,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    Text(Texts.BUDGET_HINT, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        Texts.BUDGET_NO_FIGURES,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onClick = onEdit) { Text(Texts.BUDGET_EDIT) }
    }
}

/** One field with the current limit; an empty field removes the budget, a wrong one keeps the dialog open. */
@Composable
fun BudgetDialog(budget: MonthlyBudget, onSave: (String) -> Boolean, onDismiss: () -> Unit) {
    var limit by rememberSaveable { mutableStateOf(budget.limitPln?.let { TripsFormatting.plain(it, 2) }.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Texts.BUDGET_DIALOG_TITLE) },
        text = {
            // The field and the keyboard: the dialog scrolls, so Save stays reachable on a small screen (C41).
            Column(modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())) {
                Text(Texts.BUDGET_BODY, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it; invalid = false },
                    label = { Text(Texts.BUDGET_FIELD) },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = if (invalid) ({ Text(Texts.BUDGET_INVALID) }) else null,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(limit)) onDismiss() else invalid = true }) { Text(Texts.ECO_SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Texts.ECO_CANCEL) } },
    )
}
