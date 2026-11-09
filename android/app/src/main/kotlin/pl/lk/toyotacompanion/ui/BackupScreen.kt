package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId

/** MIME types the import accepts: file managers label a `.json` file differently. */
private val ARCHIVE_MIME_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/**
 * "Kopia archiwum" (PRD F7, docs/SPEC.md §14.2): export of the whole archive to a
 * file chosen in the system picker, after a warning about the personal data in it
 * (docs/SECURITY.md), and import of such a file. The picker returns a `Uri`; the
 * controller only sees its text, so it stays free of Android types.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    controller: BackupController,
    hasTrips: Boolean,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    BackHandler(onBack = onBack)
    val state by controller.state.collectAsState()
    var warnsBeforeExport by rememberSaveable { mutableStateOf(false) }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        uri -> if (uri != null) controller.exportTo(uri.toString())
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri -> if (uri != null) controller.importFrom(uri.toString())
    }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        uri -> if (uri != null) controller.exportTripsCsv(uri.toString())
    }
    val createRefuelsCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        uri -> if (uri != null) controller.exportRefuelsCsv(uri.toString())
    }
    val createExpensesCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        uri -> if (uri != null) controller.exportExpensesCsv(uri.toString())
    }

    LaunchedEffect(Unit) { controller.open() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Texts.BACKUP_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardCard(Texts.BACKUP_TITLE, AppIcons.Save) {
                Text(Texts.BACKUP_EXPLANATION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val last = state.lastExport
                if (last != null) {
                    StatTile(backupDate(last, zone), Texts.BACKUP_TITLE, Palette.Green)
                } else {
                    IconText(
                        AppIcons.History,
                        Texts.BACKUP_NONE_YET,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.error?.let { Box { ErrorCard(it) } }
                state.message?.let { IconText(AppIcons.Info, it, style = MaterialTheme.typography.bodyMedium, color = readable(Palette.Blue)) }

                Button(
                    onClick = { warnsBeforeExport = true },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(AppIcons.Upload, Texts.BACKUP_EXPORT)
                }
                OutlinedButton(
                    onClick = { openFile.launch(ARCHIVE_MIME_TYPES) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(AppIcons.Download, Texts.BACKUP_IMPORT)
                }
            }

            DashboardCard(Texts.CSV_SECTION, AppIcons.BarChart) {
                Text(Texts.CSV_EXPLANATION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { createCsv.launch(tripsCsvFileName(LocalDate.now(zone))) },
                    enabled = hasTrips && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(AppIcons.Description, Texts.CSV_EXPORT)
                }
                Button(
                    onClick = { createRefuelsCsv.launch(refuelsCsvFileName(LocalDate.now(zone))) },
                    // null = count not known yet: enabled, so a failed count read never blocks the export.
                    enabled = state.refuelsStored != 0 && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(AppIcons.LocalGasStation, Texts.CSV_EXPORT_REFUELS)
                }
                Button(
                    onClick = { createExpensesCsv.launch(expensesCsvFileName(LocalDate.now(zone))) },
                    enabled = state.expensesStored != 0 && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(AppIcons.ReceiptLong, Texts.CSV_EXPORT_EXPENSES)
                }
            }

            // Auto Backup to the owner's Google Drive (ADR-015, docs/SPEC.md §14.2).
            DashboardCard(Texts.BACKUP_CLOUD_SECTION, AppIcons.Cloud) {
                Text(Texts.BACKUP_CLOUD_EXPLANATION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { controller.requestCloudBackup() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(AppIcons.CloudUpload, Texts.BACKUP_CLOUD_NOW)
                }
                if (state.cloudRequested) {
                    IconText(AppIcons.Info, Texts.BACKUP_CLOUD_REQUESTED, style = MaterialTheme.typography.bodyMedium, color = readable(Palette.Blue))
                }
                state.cloudError?.let { Box { ErrorCard(it) } }
                IconText(
                    AppIcons.Smartphone,
                    Texts.BACKUP_CLOUD_HINT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (warnsBeforeExport) {
        AlertDialog(
            onDismissRequest = { warnsBeforeExport = false },
            title = { Text(Texts.BACKUP_WARNING_TITLE) },
            text = { Text(Texts.BACKUP_WARNING_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    warnsBeforeExport = false
                    createFile.launch(archiveFileName(LocalDate.now(zone)))
                }) { Text(Texts.BACKUP_WARNING_OK) }
            },
            dismissButton = {
                TextButton(onClick = { warnsBeforeExport = false }) { Text(Texts.BACKUP_WARNING_CANCEL) }
            },
        )
    }
}

/** Suggested file name, e.g. `toyota-companion-2026-10-31.json` — no VIN in it (docs/SECURITY.md). */
fun archiveFileName(day: LocalDate): String = "toyota-companion-$day.json"
