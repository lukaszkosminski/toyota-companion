package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.Refuel

/**
 * The question that pops up when a refuel waiting for a price is seen while the
 * app is open (docs/SPEC.md §13.3, PRD F2c): one line about the entry, one field
 * for the price, and three ways out — save, "Później" (this entry only), "Więcej"
 * (the full form of C3).
 *
 * Only the price is asked for here: liters and total cost need the room of
 * [RefuelFormScreen], which is also where the notification lands.
 *
 * @param zone zone the timestamp of [entry] is shown in.
 */
@Composable
fun RefuelPromptDialog(
    entry: Refuel,
    priceText: String,
    error: String?,
    busy: Boolean,
    onPriceChange: (String) -> Unit,
    onSave: () -> Unit,
    onLater: () -> Unit,
    onMore: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val priceFocus = remember { FocusRequester() }

    // The whole point of F2c is that the user can type right away.
    showKeyboardOnFocus(priceFocus, entry.id)

    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(Texts.REFUEL_NOTIFICATION_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    Texts.refuelPromptBody(
                        RefuelFormatting.time(entry.ts, zone),
                        RefuelFormatting.levelJump(entry.levelBefore, entry.levelAfter),
                    ),
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = onPriceChange,
                    label = { Text(Texts.REFUEL_FIELD_PRICE) },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.focusRequester(priceFocus),
                )
                // The message stays inside the dialog: the answer is still being typed.
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !busy) { Text(Texts.REFUEL_SAVE) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onLater, enabled = !busy) { Text(Texts.REFUEL_PROMPT_LATER) }
                TextButton(onClick = onMore, enabled = !busy) { Text(Texts.REFUEL_PROMPT_MORE) }
            }
        },
    )
}
