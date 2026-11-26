package dev.livin.nammaoorufood.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.livin.nammaoorufood.AppVariant
import dev.livin.nammaoorufood.network.Role
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginScreen(variant: AppVariant, notice: String? = null, viewModel: LoginViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginContent(state, notice, variant.role, viewModel::onIntent)
}

@Composable
fun LoginContent(state: LoginState, notice: String?, role: Role, onIntent: (LoginIntent) -> Unit) {
    Box(Modifier.fillMaxSize().safeContentPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 420.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (state.registering) "Create account" else "Login", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(if (state.registering) "Register as ${if (role == Role.HOTEL_OWNER) "a hotel owner" else "a customer"}" else "Sign in to continue", style = MaterialTheme.typography.bodyLarge)
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
            if (state.registering) {
                OutlinedTextField(
                    state.displayName, { onIntent(LoginIntent.DisplayNameChanged(it)) },
                    label = { Text("Display name") }, singleLine = true,
                    enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    state.phone, { onIntent(LoginIntent.PhoneChanged(it)) },
                    label = { Text("Phone (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
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
            if (state.registering) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    state.confirmPassword, { onIntent(LoginIntent.ConfirmPasswordChanged(it)) },
                    label = { Text("Confirm password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onIntent(if (state.registering) LoginIntent.Register(role) else LoginIntent.Submit) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (state.registering) "Create account" else "Sign in")
                }
            }
            if (role != Role.ADMIN) {
                TextButton(enabled = !state.loading, onClick = { onIntent(LoginIntent.SetRegistration(!state.registering)) }) {
                    Text(if (state.registering) "Already have an account? Sign in" else "New here? Create an account")
                }
            }
        }
    }
}
