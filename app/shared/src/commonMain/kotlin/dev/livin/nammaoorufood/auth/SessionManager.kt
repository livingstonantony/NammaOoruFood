package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.network.AuthResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LoginOutcome {
    data object Success : LoginOutcome
    data class Failure(val message: String) : LoginOutcome
}

/** Single owner of the authentication state and credentials. */
class SessionManager(
    private val api: AuthApi,
    private val store: SessionStore,
    private val onSessionCleared: () -> Unit = {},
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Initializing)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _logoutNotice = MutableStateFlow<String?>(null)

    /** Set when the user logged out locally but the server could not confirm revoking the session. */
    val logoutNotice: StateFlow<String?> = _logoutNotice.asStateFlow()

    private val mutex = Mutex()
    private var accessToken: String? = null
    private var refreshToken: String? = null

    fun currentAccessToken(): String? = accessToken

    /** Restores the persisted session at startup; never exposes protected UI before this completes. */
    suspend fun restore() = mutex.withLock {
        val stored = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeClear()
            _state.value = AuthState.Unauthenticated
            return@withLock
        }
        if (stored == null) {
            _state.value = AuthState.Unauthenticated
            return@withLock
        }
        refreshToken = stored.refreshToken
        when (val result = api.refresh(stored.refreshToken)) {
            is ApiResult.Success -> adopt(result.value)
            is ApiResult.Rejected ->
                if (result.unauthorized) invalidate("Your session has expired. Please sign in again.")
                else _state.value = AuthState.Authenticated(stored.user)
            // Offline start: keep the user signed in; the access token is obtained lazily on the first request.
            is ApiResult.NetworkError -> _state.value = AuthState.Authenticated(stored.user)
        }
    }

    suspend fun login(username: String, password: String): LoginOutcome = mutex.withLock {
        _logoutNotice.value = null
        when (val result = api.login(username.trim(), password)) {
            is ApiResult.Success -> {
                adopt(result.value)
                LoginOutcome.Success
            }
            is ApiResult.Rejected -> LoginOutcome.Failure(
                if (result.unauthorized) "Invalid username or password" else result.message,
            )
            is ApiResult.NetworkError -> LoginOutcome.Failure("Cannot reach the server. Check your connection.")
        }
    }

    /**
     * Refreshes the access token. Concurrent callers are serialized, and a caller that
     * lost the race reuses the token the winner obtained instead of rotating again.
     */
    suspend fun refreshAccessToken(staleToken: String?): String? = mutex.withLock {
        if (accessToken != null && accessToken != staleToken) return@withLock accessToken
        val token = refreshToken ?: return@withLock null
        when (val result = api.refresh(token)) {
            is ApiResult.Success -> {
                adopt(result.value)
                accessToken
            }
            is ApiResult.Rejected -> {
                if (result.unauthorized) invalidate("Your session has expired. Please sign in again.")
                null
            }
            is ApiResult.NetworkError -> null
        }
    }

    /** Local credentials are always cleared; returns false if server revocation could not be confirmed. */
    suspend fun logout(): Boolean = mutex.withLock {
        val token = refreshToken
        clearLocal()
        _state.value = AuthState.Unauthenticated
        val confirmed = token == null || api.logout(token) is ApiResult.Success
        _logoutNotice.value = if (confirmed) null else "Signed out on this device, but the server could not confirm ending the session."
        confirmed
    }

    private suspend fun adopt(response: AuthResponse) {
        accessToken = response.accessToken
        refreshToken = response.refreshToken
        try {
            store.save(StoredSession(response.refreshToken, response.user))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Session still works for this run; it just will not survive a restart.
        }
        _state.value = AuthState.Authenticated(response.user)
    }

    private suspend fun invalidate(reason: String) {
        clearLocal()
        _state.value = AuthState.ReauthenticationRequired(reason)
    }

    private suspend fun clearLocal() {
        accessToken = null
        refreshToken = null
        safeClear()
        onSessionCleared()
    }

    private suspend fun safeClear() {
        try {
            store.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
