package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/**
 * Price form of one refuel (docs/SPEC.md §11.3, PRD F2): price, liters or amount —
 * the domain derives what is missing (docs/SPEC.md §2).
 *
 * The typed values stay in `rememberSaveable`: they are not secrets
 * (docs/SECURITY.md) and a rotation must not throw away a half-typed receipt.
 * The write itself belongs to [RefuelController] and to the application scope, so
 * leaving this screen does not cancel it.
 *
 * @param id which refuel is edited
 * @param onBackToList back to the list, also after a write went through
 * @param focusPriceOnOpen open the keyboard in the price field: the form reached
 *   from a notification tap is a direct answer, not a browse (docs/SPEC.md §13.1)
 * @param zone zone the timestamp of the entry is shown in
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelFormScreen(
    controller: RefuelController,
    id: String,
    onBackToList: () -> Unit,
    focusPriceOnOpen: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onBackToList)
    val state by controller.state.collectAsState()
    val entry = state.refuels.firstOrNull { it.id == id }

    var priceText by rememberSaveable { mutableStateOf("") }
    var litersText by rememberSaveable { mutableStateOf("") }
    var totalText by rememberSaveable { mutableStateOf("") }
    var filledToFull by rememberSaveable { mutableStateOf(false) }
    var asksAboutDismissing by remember { mutableStateOf(false) }
    val priceFocus = remember { FocusRequester() }

    LaunchedEffect(id) { controller.openForm(id) }
    // The controller reloads the list itself; the form only leaves when its own write is done.
    LaunchedEffect(state.savedId) {
        if (state.savedId == id) {
            onBackToList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.REFUEL_FORM_TITLE) },
                navigationIcon = { TextButton(onClick = onBackToList) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entry == null) {
                // The entry was answered meanwhile (another screen, another phone): say it plainly.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (state.loading) Texts.LOADING else Texts.REFUEL_STALE_ENTRY)
                }
            } else {
                val litersGiven = parseRefuelAmount(litersText) is RefuelAmount.Given
                val busy = state.busyId == id

                // Called from the notification: put the cursor in the price and open the keyboard.
                // Only here, where the field exists: on a cold start the form shows the loading
                // text first, and a focus asked for before the field is composed is lost for good.
                if (focusPriceOnOpen) showKeyboardOnFocus(priceFocus, id)

                Text(
                    RefuelFormatting.time(entry.ts, zone) +
                        Texts.SEPARATOR + RefuelFormatting.levelJump(entry.levelBefore, entry.levelAfter) +
                        Texts.SEPARATOR + RefuelFormatting.odometer(entry.odometerKm),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                state.formError?.let { Box(Modifier.padding(horizontal = 16.dp)) { ErrorCard(it) } }

                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text(Texts.REFUEL_FIELD_PRICE) },
                    supportingText = { FieldNote(state, RefuelField.Price) },
                    isError = state.formFieldErrors.any { it.field == RefuelField.Price },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(priceFocus),
                )
                OutlinedTextField(
                    value = litersText,
                    onValueChange = { litersText = it },
                    label = { Text(Texts.REFUEL_FIELD_LITERS) },
                    supportingText = { FieldNote(state, RefuelField.Liters) },
                    isError = state.formFieldErrors.any { it.field == RefuelField.Liters },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                OutlinedTextField(
                    value = totalText,
                    onValueChange = { totalText = it },
                    label = { Text(Texts.REFUEL_FIELD_TOTAL) },
                    supportingText = { FieldNote(state, RefuelField.Total) },
                    isError = state.formFieldErrors.any { it.field == RefuelField.Total },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Texts.REFUEL_FILLED_FULL, style = MaterialTheme.typography.bodyLarge)
                    }
                    // "Do pełna" without liters is what the domain refuses (docs/SPEC.md §2).
                    Switch(
                        checked = filledToFull && litersGiven,
                        onCheckedChange = { filledToFull = it },
                        enabled = litersGiven && !busy,
                    )
                }

                Button(
                    onClick = { controller.confirm(id, priceText, litersText, totalText, filledToFull) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(Texts.REFUEL_SAVE)
                }
                OutlinedButton(
                    onClick = { asksAboutDismissing = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(Texts.REFUEL_DISMISS)
                }
            }
        }
    }

    if (asksAboutDismissing) {
        AlertDialog(
            onDismissRequest = { asksAboutDismissing = false },
            title = { Text(Texts.REFUEL_DISMISS_TITLE) },
            text = { Text(Texts.REFUEL_DISMISS_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    asksAboutDismissing = false
                    controller.dismiss(id)
                }) {
                    Text(Texts.REFUEL_DISMISS_YES)
                }
            },
            dismissButton = {
                TextButton(onClick = { asksAboutDismissing = false }) { Text(Texts.REFUEL_DISMISS_NO) }
            },
        )
    }
}

/** Note under one amount field, shown only when that field is the problem. */
@Composable
private fun FieldNote(state: RefuelUiState, field: RefuelField) {
    val error = state.formFieldErrors.firstOrNull { it.field == field }
    if (error != null) {
        Text(error.message)
    }
}
