package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import pl.lk.toyotacompanion.settings.CarValuation

/** What the fields of the car valuation form hold (PRD F55, docs/SPEC.md §55). */
internal sealed interface CarValuationCheck {
    /** Both amounts are filled and the dates are in order. */
    data class Valid(val valuation: CarValuation) : CarValuationCheck

    /** Both amount fields are empty: the owner has no valuation, so the setting is removed. */
    object Empty : CarValuationCheck

    /** One field is wrong; [message] is one of the [Texts] constants. */
    data class Invalid(val message: String) : CarValuationCheck
}

/**
 * Reads the four fields of the form (PRD F55, docs/SPEC.md §55): the purchase price must be positive, the estimated value
 * may be 0 (the car is worth nothing), and the valuation cannot be older than the purchase. Amounts use the same syntax as
 * the expense form ([parseExpenseAmount]).
 */
internal fun parseCarValuation(
    purchaseText: String,
    purchaseDate: LocalDate,
    valueText: String,
    valueDate: LocalDate,
): CarValuationCheck {
    if (purchaseText.isBlank() && valueText.isBlank()) return CarValuationCheck.Empty
    val purchase = parseExpenseAmount(purchaseText) ?: return CarValuationCheck.Invalid(Texts.CAR_PURCHASE_INVALID)
    val value = parseExpenseAmount(valueText, allowZero = true) ?: return CarValuationCheck.Invalid(Texts.CAR_VALUE_INVALID)
    if (valueDate.isBefore(purchaseDate)) return CarValuationCheck.Invalid(Texts.CAR_VALUE_DATE_ORDER)
    return CarValuationCheck.Valid(CarValuation(purchase, purchaseDate, value, valueDate))
}

/**
 * The car valuation card in "Opcje" (PRD F55, docs/SPEC.md §55): four fields, two of them dates picked like in the expense
 * form. The stored valuation fills the fields; both amount fields empty plus "Zapisz" removes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarValuationCard(
    state: CarValuationUiState,
    onSave: (CarValuation) -> Unit,
    onRemove: () -> Unit,
) {
    val stored = state.valuation
    var purchaseText by rememberSaveable(stored) { mutableStateOf(stored?.purchasePln?.toPlainString().orEmpty()) }
    var valueText by rememberSaveable(stored) { mutableStateOf(stored?.valuePln?.toPlainString().orEmpty()) }
    var purchaseEpochDay by rememberSaveable(stored) {
        mutableLongStateOf((stored?.purchaseDate ?: LocalDate.now()).toEpochDay())
    }
    var valueEpochDay by rememberSaveable(stored) {
        mutableLongStateOf((stored?.valueDate ?: LocalDate.now()).toEpochDay())
    }
    var invalid by rememberSaveable(stored) { mutableStateOf<String?>(null) }
    var pickingPurchase by rememberSaveable { mutableStateOf(false) }
    var pickingValue by rememberSaveable { mutableStateOf(false) }

    DashboardCard(Texts.CAR_VALUE_TITLE, AppIcons.Payments) {
        Text(Texts.CAR_VALUE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.loadFailed) {
            Text(Texts.CAR_VALUE_READ_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = purchaseText,
            onValueChange = { purchaseText = it; invalid = null },
            label = { Text(Texts.CAR_PURCHASE_FIELD) },
            isError = invalid == Texts.CAR_PURCHASE_INVALID,
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedButton(
            onClick = { pickingPurchase = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconLabel(AppIcons.CalendarMonth, Texts.CAR_PURCHASE_DATE + ": " + TripsFormatting.date(LocalDate.ofEpochDay(purchaseEpochDay)))
        }
        OutlinedTextField(
            value = valueText,
            onValueChange = { valueText = it; invalid = null },
            label = { Text(Texts.CAR_VALUE_FIELD) },
            isError = invalid == Texts.CAR_VALUE_INVALID,
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedButton(
            onClick = { pickingValue = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconLabel(AppIcons.CalendarMonth, Texts.CAR_VALUE_DATE + ": " + TripsFormatting.date(LocalDate.ofEpochDay(valueEpochDay)))
        }
        if (stored == null && purchaseText.isBlank() && valueText.isBlank()) {
            Text(Texts.CAR_VALUE_EMPTY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val message = invalid ?: state.error
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val check = parseCarValuation(
                        purchaseText,
                        LocalDate.ofEpochDay(purchaseEpochDay),
                        valueText,
                        LocalDate.ofEpochDay(valueEpochDay),
                    )
                    when (check) {
                        is CarValuationCheck.Valid -> {
                            invalid = null
                            onSave(check.valuation)
                        }
                        CarValuationCheck.Empty -> {
                            invalid = null
                            onRemove()
                        }
                        is CarValuationCheck.Invalid -> invalid = check.message
                    }
                },
                enabled = !state.busy,
            ) { Text(Texts.CAR_VALUE_SAVE) }
            if (stored != null) {
                OutlinedButton(onClick = onRemove, enabled = !state.busy) { Text(Texts.CAR_VALUE_REMOVE) }
            }
        }
    }

    if (pickingPurchase) {
        ValuationDatePicker(
            epochDay = purchaseEpochDay,
            onPick = { purchaseEpochDay = it; pickingPurchase = false },
            onDismiss = { pickingPurchase = false },
        )
    }
    if (pickingValue) {
        ValuationDatePicker(
            epochDay = valueEpochDay,
            onPick = { valueEpochDay = it; pickingValue = false },
            onDismiss = { pickingValue = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValuationDatePicker(epochDay: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.ofEpochDay(epochDay).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay())
                } ?: onDismiss()
            }) { Text(Texts.EXPENSE_SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Texts.EXPENSE_CANCEL) } },
    ) { DatePicker(state = pickerState) }
}
