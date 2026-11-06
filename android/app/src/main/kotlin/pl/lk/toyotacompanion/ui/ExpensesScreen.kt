package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "Wydatki": everything the car costs except fuel - service, inspection, tyres, insurance... Each entry has its own date, so
 * an expense from last month (or last year) is entered with that date. [fuelTotal] is the estimated fuel money of the trips,
 * shown next to the sum of the other expenses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(controller: ExpensesController, fuelTotal: BigDecimal?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.load() }
    var adding by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🧾 " + Texts.EXPENSES_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }) { Text("➕ " + Texts.EXPENSES_ADD) }
        },
    ) { innerPadding ->
        when {
            state.loading -> Centered(innerPadding) { CircularProgressIndicator() }
            state.failed -> Centered(innerPadding) { Text(Texts.EXPENSES_READ_ERROR) }
            state.expenses.isEmpty() -> Centered(innerPadding) { Text(Texts.EXPENSES_EMPTY, style = MaterialTheme.typography.bodyLarge) }
            else -> {
                val expenses = state.expenses
                val other = expenses.fold(BigDecimal.ZERO) { a, e -> a + e.amount }
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        DashboardCard(Texts.EXPENSES_FUEL_VS_OTHER, "⚖️") {
                            val fuel = fuelTotal ?: BigDecimal.ZERO
                            val sum = fuel + other
                            val otherShare = if (sum.signum() > 0) other.toFloat() / sum.toFloat() else 0f
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                RingGauge(otherShare, color = Palette.Violet) {
                                    Text(TripsFormatting.plain(BigDecimal(otherShare * 100.0), 0) + "%", style = MaterialTheme.typography.titleMedium)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StatTile(TripsFormatting.money(fuel), Texts.EXPENSES_FUEL, Palette.Orange, Modifier.weight(1f))
                                        StatTile(TripsFormatting.money(other), Texts.EXPENSES_OTHER, Palette.Violet, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        DashboardCard(Texts.EXPENSES_BY_CATEGORY, "🧩") {
                            val totals = categoryTotals(expenses)
                            val top = totals.first().total.toFloat().coerceAtLeast(1f)
                            totals.forEachIndexed { i, row ->
                                MeterRow(
                                    row.category.emoji + " " + Texts.expenseCategoryName(row.category),
                                    TripsFormatting.money(row.total),
                                    row.total.toFloat() / top,
                                    Palette.Series[i % Palette.Series.size],
                                )
                            }
                        }
                    }
                    val months = monthTotals(expenses).takeLast(12)
                    if (months.size > 1) {
                        item {
                            DashboardCard(Texts.EXPENSES_BY_MONTH, "📊") {
                                BarChart(
                                    months.map { ChartBar(it.total.toFloat(), ConsumptionFormatting.monthShort(it.month), TripsFormatting.plain(it.total, 0)) },
                                    color = Palette.Violet,
                                )
                            }
                        }
                    }
                    item { SectionHeader("🗂️", Texts.EXPENSES_HISTORY) }
                    items(expenses, key = { it.id }) { expense ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            EmojiBadge(expense.category.emoji, Palette.Violet)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(Texts.expenseCategoryName(expense.category), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    expense.date.toString() + (expense.note?.let { Texts.SEPARATOR + it } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(TripsFormatting.money(expense.amount), style = MaterialTheme.typography.titleMedium, color = readable(Palette.Violet))
                            TextButton(onClick = { controller.delete(expense.id) }) { Text("🗑️") }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AddExpenseDialog(
            onSave = { date, category, amount, note -> controller.add(date, category, amount, note) },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun Centered(innerPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(
    onSave: (LocalDate, ExpenseCategory, String, String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var category by rememberSaveable { mutableStateOf(ExpenseCategory.SERVICE) }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Texts.EXPENSES_ADD) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in ExpenseCategory.entries) {
                        FilterChip(
                            selected = c == category,
                            onClick = { category = c },
                            label = { Text(c.emoji + " " + Texts.expenseCategoryName(c)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it; invalid = false },
                    label = { Text(Texts.EXPENSE_AMOUNT_FIELD) },
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(Texts.EXPENSE_AMOUNT_INVALID) }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(Texts.EXPENSE_NOTE_FIELD) },
                    singleLine = true,
                )
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("📅 " + Texts.EXPENSE_DATE + ": " + LocalDate.ofEpochDay(epochDay))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onSave(LocalDate.ofEpochDay(epochDay), category, amount, note)) onDismiss() else invalid = true
            }) { Text(Texts.EXPENSE_SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Texts.EXPENSE_CANCEL) } },
    )

    if (pickingDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(epochDay).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
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
