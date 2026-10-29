package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Login form (docs/SPEC.md §9.2).
 *
 * The password is `remember`, never `rememberSaveable`: the saved-instance
 * Bundle may be written to disk, and the password must not be in it
 * (docs/SECURITY.md).
 */
@Composable
fun LoginScreen(state: UiState.LoggedOut, onLogin: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Texts.LOGIN_TITLE, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(Texts.LOGIN_FIELD) },
            singleLine = true,
            enabled = !state.busy,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(Texts.PASSWORD_FIELD) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !state.busy,
        )
        state.error?.let { Text(Texts.loginError(it), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { onLogin(username, password) }, enabled = !state.busy) {
            Text(if (state.busy) Texts.LOGIN_BUSY else Texts.LOGIN_BUTTON)
        }
    }
}
