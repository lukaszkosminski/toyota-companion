package pl.lk.toyotacompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** "Opcje": settings of the app itself; for now the language, applied at once and remembered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(onSetLanguage: (AppLanguage) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚙️ " + Texts.OPTIONS_TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text(Texts.BACK) } },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DashboardCard(Texts.LANGUAGE_TITLE, "🌐") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (language in AppLanguage.entries) {
                        FilterChip(
                            selected = Lang.current == language,
                            onClick = { onSetLanguage(language) },
                            label = { Text((if (language == AppLanguage.PL) "🇵🇱 " else "🇬🇧 ") + language.label) },
                        )
                    }
                }
            }
        }
    }
}
