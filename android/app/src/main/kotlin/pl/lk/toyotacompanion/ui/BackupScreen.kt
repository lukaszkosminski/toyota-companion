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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Texts.BACKUP_EXPLANATION, style = MaterialTheme.typography.bodyMedium)
            Text(
                state.lastExport?.let { Texts.lastBackup(backupDate(it, zone)) } ?: Texts.BACKUP_NONE_YET,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.error?.let { Box { ErrorCard(it) } }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Button(
                onClick = { warnsBeforeExport = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(Texts.BACKUP_EXPORT)
            }
            OutlinedButton(
                onClick = { openFile.launch(ARCHIVE_MIME_TYPES) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(Texts.BACKUP_IMPORT)
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
