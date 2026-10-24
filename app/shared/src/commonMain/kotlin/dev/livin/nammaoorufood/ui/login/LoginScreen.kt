package dev.livin.nammaoorufood.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(notice: String? = null, viewModel: LoginViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginContent(state, notice, viewModel::onIntent)
}

@Composable
fun LoginContent(state: LoginState, notice: String?, onIntent: (LoginIntent) -> Unit) {
    Box(Modifier.fillMaxSize().safeContentPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 420.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Namma Ooru Food", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text("Sign in to continue", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            (state.error ?: notice)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = state.username,
                onValueChange = { onIntent(LoginIntent.UsernameChanged(it)) },
                label = { Text("Username") },
                singleLine = true,
                isError = state.usernameError != null,
                supportingText = state.usernameError?.let { { Text(it) } },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = { onIntent(LoginIntent.PasswordChanged(it)) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { onIntent(LoginIntent.TogglePasswordVisibility) }) {
                        Text(if (state.passwordVisible) "Hide" else "Show")
                    }
                },
                isError = state.passwordError != null,
                supportingText = state.passwordError?.let { { Text(it) } },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onIntent(LoginIntent.Submit) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Sign in")
                }
            }
        }
    }
}
