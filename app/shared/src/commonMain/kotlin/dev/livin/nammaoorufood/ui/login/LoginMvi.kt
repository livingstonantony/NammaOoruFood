package dev.livin.nammaoorufood.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.livin.nammaoorufood.auth.LoginOutcome
import dev.livin.nammaoorufood.auth.SessionManager
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
)

sealed interface LoginIntent {
    data class UsernameChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data object TogglePasswordVisibility : LoginIntent
    data object Submit : LoginIntent
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
