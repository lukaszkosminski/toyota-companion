package pl.lk.toyotacompanion.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import pl.lk.toyotacompanion.core.domain.TyreSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import pl.lk.toyotacompanion.receipts.ReceiptException
import pl.lk.toyotacompanion.receipts.ReceiptOwner
import pl.lk.toyotacompanion.receipts.ReceiptRepository
import pl.lk.toyotacompanion.receipts.receiptKey

/**
 * "Wydatki": everything the car costs except fuel - service, inspection, tyres, insurance... Each entry has its own date, so
 * an expense from last month (or last year) is entered with that date. [fuelTotal] is the estimated fuel money of the trips,
 * shown next to the sum of the other expenses, as a row among the categories and in the monthly bars ([fuelByMonth]) - the fuel
 * is most of what the car costs, so it is part of the picture even before the first other expense is entered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    controller: ExpensesController,
    fuelTotal: BigDecimal?,
    onBack: () -> Unit,
    fuelByMonth: Map<MonthKey, BigDecimal> = emptyMap(),
    tyres: TyreController,
    receipts: ReceiptRepository,
) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.load() }
    // The photos of the expenses (PRD F59): which rows have one, and the one shown in the window.
    val receiptIds by receipts.attached.collectAsState()
    LaunchedEffect(Unit) { receipts.refresh() }
    var viewingExpense by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    // The tyre card (PRD F56, docs/SPEC.md §56): its own read, and its own dialogs.
    val tyreState by tyres.state.collectAsState()
    LaunchedEffect(Unit) { tyres.load() }
    var changingTyres by rememberSaveable { mutableStateOf(false) }
    var editingThresholds by rememberSaveable { mutableStateOf(false) }
    var removingTyre by rememberSaveable { mutableStateOf(false) }
    TyreDialogs(
        tyres = tyres,
        tyreState = tyreState,
        changing = changingTyres,
        editingThresholds = editingThresholds,
        removing = removingTyre,
        onChanged = { changingTyres = false },
        onThresholdsChanged = { editingThresholds = false },
        onRemoved = { removingTyre = false },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.EXPENSES_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }) { IconLabel(AppIcons.Add, Texts.EXPENSES_ADD) }
        },
    ) { innerPadding ->
        // One list in every state (PRD F56, docs/SPEC.md §56): the tyre card is always its first item, so it is reachable without expenses,
        // and the floating button sits over the bottom padding, not over the last card.
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
                TyreCard(
                    state = tyreState,
                    today = LocalDate.now(),
                    onChange = { changingTyres = true },
                    onThresholds = { editingThresholds = true },
                    onRemove = { removingTyre = true },
                )
            }
            when {
                state.loading -> item { Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                state.failed -> item { Text(Texts.EXPENSES_READ_ERROR) }
                else -> expenseItems(
                    expenses = state.expenses,
                    fuelTotal = fuelTotal,
                    fuelByMonth = fuelByMonth,
                    hasReceipt = { receiptKey(ReceiptOwner.EXPENSE, it) in receiptIds },
                    onShowReceipt = { viewingExpense = it },
                    onDelete = { controller.delete(it) },
                )
            }
        }
    }

    if (adding) {
        AddExpenseDialog(
            receipts = receipts,
            onSave = { date, category, amount, note, photo -> controller.add(date, category, amount, note, photo) },
            onDismiss = { adding = false },
        )
    }
    viewingExpense?.let { id ->
        ReceiptViewerDialog(ReceiptOwner.EXPENSE, id, receipts) { viewingExpense = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(
    receipts: ReceiptRepository,
    onSave: (LocalDate, ExpenseCategory, String, String, String?) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // A photo picked here is copied at once but linked only by Save; a cancelled form deletes the copy (PRD F59).
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var photoError by rememberSaveable { mutableStateOf(false) }
    val pickPhoto = rememberReceiptPicker { uri ->
        importing = true
        photoError = false
        scope.launch {
            try {
                val name = receipts.importPending(uri)
                pendingPhoto?.let { receipts.discard(it) }
                pendingPhoto = name
            } catch (e: ReceiptException) {
                photoError = true
            } finally {
                importing = false
            }
        }
    }
    val dropPhoto = {
        pendingPhoto?.let { name -> scope.launch { receipts.discard(name) } }
        pendingPhoto = null
    }
    var category by rememberSaveable { mutableStateOf(ExpenseCategory.SERVICE) }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var epochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            dropPhoto()
            onDismiss()
        },
        title = { Text(Texts.EXPENSES_ADD) },
        text = {
            // Scrolls with the keyboard open, so the note and the photo stay reachable on a small screen.
            Column(
                modifier = Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in ExpenseCategory.entries) {
                        FilterChip(
                            selected = c == category,
                            onClick = { category = c },
                            label = { Text(Texts.expenseCategoryName(c)) },
                            leadingIcon = { Icon(categoryIcon(c), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
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
                    IconLabel(AppIcons.CalendarMonth, Texts.EXPENSE_DATE + ": " + LocalDate.ofEpochDay(epochDay))
                }
                val photo = pendingPhoto
                if (photo != null) {
                    ReceiptImage(photo, receipts, RECEIPT_THUMBNAIL_SIDE, Modifier.heightIn(max = 160.dp))
                    TextButton(onClick = dropPhoto) { IconLabel(AppIcons.Delete, Texts.RECEIPT_REMOVE) }
                } else {
                    OutlinedButton(onClick = pickPhoto, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                        IconLabel(AppIcons.Description, Texts.RECEIPT_ADD)
                    }
                }
                if (photoError) Text(Texts.RECEIPT_IMPORT_ERROR, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onSave(LocalDate.ofEpochDay(epochDay), category, amount, note, pendingPhoto)) {
                    // The photo is linked now: the form must not delete it on the way out.
                    pendingPhoto = null
                    onDismiss()
                } else {
                    invalid = true
                }
            }) { Text(Texts.EXPENSE_SAVE) }
        },
        dismissButton = {
            TextButton(onClick = {
                dropPhoto()
                onDismiss()
            }) { Text(Texts.EXPENSE_CANCEL) }
        },
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

@Composable
private fun TyreDialogs(
    tyres: TyreController,
    tyreState: TyreUiState,
    changing: Boolean,
    editingThresholds: Boolean,
    removing: Boolean,
    onChanged: () -> Unit,
    onThresholdsChanged: () -> Unit,
    onRemoved: () -> Unit,
) {
    if (removing) {
        // Removing is a confirmed step: the last fitting goes, with its reminders forgotten (PRD F56).
        val last = tyreState.changes.maxByOrNull { it.date }
        if (last == null) {
            onRemoved()
        } else {
            AlertDialog(
                onDismissRequest = onRemoved,
                title = { Text(Texts.TYRE_REMOVE_TITLE) },
                text = { Text(Texts.tyreRemoveBody(Texts.tyreSetName(last.set), TyreFormatting.day(last.date))) },
                confirmButton = {
                    TextButton(onClick = {
                        tyres.removeLastChange()
                        onRemoved()
                    }) { Text(Texts.TYRE_REMOVE_CONFIRM) }
                },
                dismissButton = { TextButton(onClick = onRemoved) { Text(Texts.EXPENSE_CANCEL) } },
            )
        }
    }
    if (changing) {
        val onNow = tyreState.usage?.sets?.lastOrNull()?.change?.set
        TyreChangeDialog(
            initialSet = if (onNow == TyreSet.SUMMER) TyreSet.WINTER else TyreSet.SUMMER,
            onSave = { date, set, odometer -> tyres.addChange(date, set, odometer) },
            onDismiss = onChanged,
        )
    }
    if (editingThresholds) {
        TyreThresholdDialog(
            current = tyreState.thresholds,
            onSave = { texts -> tyres.saveThresholds(texts) },
            onDismiss = onThresholdsChanged,
        )
    }
}

/** The icon of an expense category in the list and the category chips. */
private fun categoryIcon(category: ExpenseCategory): ImageVector = when (category) {
    ExpenseCategory.SERVICE -> AppIcons.Build
    ExpenseCategory.INSPECTION -> AppIcons.FactCheck
    ExpenseCategory.TYRES -> AppIcons.TireRepair
    ExpenseCategory.INSURANCE -> AppIcons.Shield
    ExpenseCategory.PARTS -> AppIcons.Construction
    ExpenseCategory.WASH -> AppIcons.LocalCarWash
    ExpenseCategory.FEES -> AppIcons.LocalParking
    ExpenseCategory.OTHER -> AppIcons.ReceiptLong
}

/** The expense rows under the tyre card: the fuel against the other costs, the categories, the months and the history (PRD F38, F37). */
private fun LazyListScope.expenseItems(
    expenses: List<Expense>,
    fuelTotal: BigDecimal?,
    fuelByMonth: Map<MonthKey, BigDecimal>,
    hasReceipt: (String) -> Boolean,
    onShowReceipt: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val other = expenses.fold(BigDecimal.ZERO) { a, e -> a + e.amount }
        item {
            DashboardCard(Texts.EXPENSES_FUEL_VS_OTHER, AppIcons.Balance) {
                val fuel = fuelTotal ?: BigDecimal.ZERO
                val sum = fuel + other
                val otherShare = if (sum.signum() > 0) other.toFloat() / sum.toFloat() else 0f
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RingGauge(otherShare, color = Palette.Violet) {
                        Text(TripsFormatting.number(BigDecimal(otherShare * 100.0), 0) + "%", style = MaterialTheme.typography.titleMedium)
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
            DashboardCard(Texts.EXPENSES_BY_CATEGORY, AppIcons.Category) {
                // The fuel of the trips is a row like a category, so the biggest cost of the car is seen next to the rest.
                val rows = buildList {
                    for (row in categoryTotals(expenses)) add(Triple(Texts.expenseCategoryName(row.category), row.total, false))
                    if (fuelTotal != null && fuelTotal.signum() > 0) add(Triple(Texts.EXPENSES_FUEL, fuelTotal, true))
                }.sortedByDescending { it.second }
                val top = rows.first().second.toFloat().coerceAtLeast(1f)
                rows.forEachIndexed { i, (label, total, isFuel) ->
                    MeterRow(
                        label,
                        TripsFormatting.money(total),
                        total.toFloat() / top,
                        if (isFuel) Palette.Orange else Palette.Series[i % Palette.Series.size],
                    )
                }
            }
        }
        val months = monthTotals(expenses, fuelByMonth).takeLast(12)
        if (months.size > 1) {
            item {
                DashboardCard(Texts.EXPENSES_BY_MONTH, AppIcons.BarChart) {
                    BarChart(
                        months.map { ChartBar(it.total.toFloat(), ConsumptionFormatting.monthShort(it.month), TripsFormatting.number(it.total, 0)) },
                        color = Palette.Violet,
                    )
                }
            }
        }
        if (expenses.isEmpty()) {
            item { Text(Texts.EXPENSES_EMPTY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item { SectionHeader(AppIcons.History, Texts.EXPENSES_HISTORY) }
        items(expenses, key = { it.id }) { expense ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconBadge(categoryIcon(expense.category), Palette.Violet)
                Column(modifier = Modifier.weight(1f)) {
                    Text(Texts.expenseCategoryName(expense.category), style = MaterialTheme.typography.titleSmall)
                    Text(
                        expense.date.toString() + (expense.note?.let { Texts.SEPARATOR + it } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(TripsFormatting.money(expense.amount), style = MaterialTheme.typography.titleMedium, color = readable(Palette.Violet))
                if (hasReceipt(expense.id)) {
                    IconButton(onClick = { onShowReceipt(expense.id) }) { Icon(AppIcons.Description, contentDescription = Texts.RECEIPT_SHOW) }
                }
                IconButton(onClick = { onDelete(expense.id) }) { Icon(AppIcons.Delete, contentDescription = Texts.EXPENSE_DELETE) }
            }
        }
}

/** The side of the photo thumbnail in the add-expense form, in pixels. */
private const val RECEIPT_THUMBNAIL_SIDE = 480
