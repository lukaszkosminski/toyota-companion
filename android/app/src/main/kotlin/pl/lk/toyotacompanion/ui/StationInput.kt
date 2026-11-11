package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Optional station at a refuel (PRD F57, docs/SPEC.md §57): a free text field and
 * chips with names the owner used before, in one horizontally scrolling row so a
 * narrow dialog keeps its height. Picking a chip only fills the field; the owner
 * can always replace it or leave it empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    label: String = Texts.REFUEL_FIELD_STATION,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            Text(Texts.STATION_SUGGESTIONS_LABEL, style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (station in suggestions) {
                    FilterChip(
                        selected = value.equals(station, ignoreCase = true),
                        onClick = { onValueChange(station) },
                        enabled = enabled,
                        label = { Text(station) },
                    )
                }
            }
        }
    }
}
