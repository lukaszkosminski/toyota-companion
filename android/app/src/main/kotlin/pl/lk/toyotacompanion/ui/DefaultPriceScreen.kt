package pl.lk.toyotacompanion.ui


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
/**
 * Default fuel price (docs/SPEC.md §12.3, PRD F2b): one amount, stored on the
 * phone, used for the trips that have no confirmed refuel before them (§4).
 *
 * The typed value stays in `rememberSaveable` and the write belongs to
 * [DefaultPriceController] in the application scope, so leaving this screen during
 * a write does not cancel it.
 *
 * @param onBackToStatus back to the status screen, also after a write went through
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultPriceScreen(
    controller: DefaultPriceController,
    prices: StationPriceController,
    onBackToStatus: () -> Unit,
) {
    BackHandler(onBack = onBackToStatus)
    val state by controller.state.collectAsState()

    // The field starts empty and is not prefilled: prefilling invites a typo that
    // silently replaces the stored price (docs/SPEC.md §12.3).
    var priceText by rememberSaveable { mutableStateOf("") }
    var asksAboutRemoving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { controller.open() }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            onBackToStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.DEFAULT_PRICE_TITLE) },
                navigationIcon = { TextButton(onClick = onBackToStatus) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardCard(Texts.DEFAULT_PRICE_TITLE, AppIcons.LocalGasStation) {
                val currentPrice = state.price
                if (currentPrice != null) {
                    Headline(
                        value = TripsFormatting.plain(currentPrice, 2),
                        unit = "zł/l",
                        caption = Texts.DEFAULT_PRICE_NOTE,
                    )
                } else {
                    // Without a stored price the card says what is missing, in the colour of a hint.
                    IconText(
                        AppIcons.LocalGasStation,
                        Texts.DEFAULT_PRICE_PROMPT_UNSET,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.error?.let { ErrorCard(it) }

                StationPricePicker(
                    controller = prices,
                    onPrice = { priceText = it },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text(Texts.REFUEL_FIELD_PRICE) },
                    supportingText = { Text(Texts.DEFAULT_PRICE_HINT) },
                    isError = state.error != null,
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = { controller.save(priceText) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(AppIcons.Save, Texts.REFUEL_SAVE)
                }
                // Removing has its own button: an empty field is a typo, not a request to remove.
                OutlinedButton(
                    onClick = { asksAboutRemoving = true },
                    enabled = !state.busy && state.price != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(AppIcons.Delete, Texts.DEFAULT_PRICE_REMOVE)
                }
            }
        }
    }

    if (asksAboutRemoving) {
        AlertDialog(
            onDismissRequest = { asksAboutRemoving = false },
            title = { Text(Texts.DEFAULT_PRICE_REMOVE_TITLE) },
            text = { Text(Texts.DEFAULT_PRICE_REMOVE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    asksAboutRemoving = false
                    controller.remove()
                }) {
                    Text(Texts.DEFAULT_PRICE_REMOVE_YES)
                }
            },
            dismissButton = {
                TextButton(onClick = { asksAboutRemoving = false }) { Text(Texts.DEFAULT_PRICE_REMOVE_NO) }
            },
        )
    }
}
