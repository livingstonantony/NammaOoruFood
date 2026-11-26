package dev.livin.nammaoorufood.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.livin.nammaoorufood.auth.LoginOutcome
import dev.livin.nammaoorufood.auth.SessionManager
import dev.livin.nammaoorufood.network.RegisterRequest
import dev.livin.nammaoorufood.network.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val loading: Boolean = false,
    val usernameError: String? = null,
    val passwordError: String? = null,
    val error: String? = null,
    val registering: Boolean = false,
    val displayName: String = "",
    val phone: String = "",
    val confirmPassword: String = "",
)

sealed interface LoginIntent {
    data class UsernameChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data object TogglePasswordVisibility : LoginIntent
    data object Submit : LoginIntent
    data class SetRegistration(val enabled: Boolean) : LoginIntent
    data class DisplayNameChanged(val value: String) : LoginIntent
    data class PhoneChanged(val value: String) : LoginIntent
    data class ConfirmPasswordChanged(val value: String) : LoginIntent
    data class Register(val role: Role) : LoginIntent
}

class LoginViewModel(private val session: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onIntent(intent: LoginIntent) {
        when (intent) {
            is LoginIntent.UsernameChanged ->
                _state.update { it.copy(username = intent.value, usernameError = null, error = null) }
            is LoginIntent.PasswordChanged ->
                _state.update { it.copy(password = intent.value, passwordError = null, error = null) }
            LoginIntent.TogglePasswordVisibility ->
                _state.update { it.copy(passwordVisible = !it.passwordVisible) }
            LoginIntent.Submit -> submit()
            is LoginIntent.SetRegistration -> if (!_state.value.loading) {
                _state.update { LoginState(username = it.username, registering = intent.enabled) }
            }
            is LoginIntent.DisplayNameChanged -> _state.update { it.copy(displayName = intent.value, error = null) }
            is LoginIntent.PhoneChanged -> _state.update { it.copy(phone = intent.value, error = null) }
            is LoginIntent.ConfirmPasswordChanged -> _state.update { it.copy(confirmPassword = intent.value, error = null) }
            is LoginIntent.Register -> register(intent.role)
        }
    }

    private fun register(role: Role) {
        val current = _state.value
        if (current.loading) return
        val username = current.username.trim().lowercase()
        val displayName = current.displayName.trim()
        val phone = current.phone.trim()
        val error = when {
            role == Role.ADMIN -> "Administrator registration is not available."
            !Regex("^[a-z0-9._-]{3,50}$").matches(username) -> "Username must be 3–50 letters, digits, '.', '_' or '-'."
            displayName.isEmpty() || displayName.length > 100 -> "Display name must be 1–100 characters."
            phone.isNotEmpty() && !Regex("^[0-9+ -]{7,20}$").matches(phone) -> "Enter a valid phone number."
            current.password.length !in 8..72 -> "Password must be 8–72 characters."
            current.password != current.confirmPassword -> "Passwords do not match."
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val outcome = session.register(RegisterRequest(username, current.password, displayName, phone.ifEmpty { null }), role)) {
                LoginOutcome.Success -> _state.update { it.copy(loading = false, registering = false, password = "", confirmPassword = "") }
                is LoginOutcome.Failure -> _state.update { it.copy(loading = false, error = outcome.message) }
            }
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.loading) return // ignore duplicate submissions
        val usernameError = if (current.username.isBlank()) "Enter your username" else null
        val passwordError = if (current.password.isEmpty()) "Enter your password" else null
        if (usernameError != null || passwordError != null) {
            _state.update { it.copy(usernameError = usernameError, passwordError = passwordError) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val outcome = session.login(current.username, current.password)) {
                LoginOutcome.Success -> _state.update { it.copy(loading = false, password = "") }
                is LoginOutcome.Failure -> _state.update { it.copy(loading = false, error = outcome.message) }
            }
        }
    }
}
