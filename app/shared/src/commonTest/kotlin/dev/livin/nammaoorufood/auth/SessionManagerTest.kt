package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.network.AuthResponse
import dev.livin.nammaoorufood.network.Role
import dev.livin.nammaoorufood.network.UserDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val user = UserDto(1, "customer", "Customer", Role.CUSTOMER)
private fun auth(n: Int) = AuthResponse("access$n", "refresh$n", 900, user)

private class FakeApi : AuthApi {
    var loginResult: ApiResult<AuthResponse> = ApiResult.Success(auth(1))
    var refreshResult: (String) -> ApiResult<AuthResponse> = { ApiResult.Success(auth(2)) }
    var logoutResult: ApiResult<Unit> = ApiResult.Success(Unit)
    val refreshCalls = mutableListOf<String>()
    val logoutCalls = mutableListOf<String>()
    override suspend fun login(username: String, password: String) = loginResult
    override suspend fun refresh(refreshToken: String): ApiResult<AuthResponse> {
        refreshCalls += refreshToken
        return refreshResult(refreshToken)
    }
    override suspend fun logout(refreshToken: String): ApiResult<Unit> {
        logoutCalls += refreshToken
        return logoutResult
    }
}

private class FailingStore : SessionStore {
    override suspend fun load(): StoredSession? = error("keystore broken")
    override suspend fun save(session: StoredSession) = error("keystore broken")
    override suspend fun clear() = error("keystore broken")
}

class SessionManagerTest {
    private val api = FakeApi()
    private val store = InMemorySessionStore()
    private var cleared = 0
    private val manager = SessionManager(api, store) { cleared++ }

    @Test
    fun startsInitializing() {
        assertEquals(AuthState.Initializing, manager.state.value)
    }

    @Test
    fun restoreWithoutSessionIsUnauthenticated() = runTest {
        manager.restore()
        assertEquals(AuthState.Unauthenticated, manager.state.value)
        assertTrue(api.refreshCalls.isEmpty())
    }

    @Test
    fun loginPersistsRefreshTokenOnly() = runTest {
        assertEquals(LoginOutcome.Success, manager.login(" customer ", "pw"))
        assertEquals(AuthState.Authenticated(user), manager.state.value)
        assertEquals("access1", manager.currentAccessToken())
        assertEquals("refresh1", store.load()?.refreshToken)
    }

    @Test
    fun invalidLoginReportsFriendlyError() = runTest {
        api.loginResult = ApiResult.Rejected(401, "bad", unauthorized = true)
        assertEquals(LoginOutcome.Failure("Invalid username or password"), manager.login("a", "b"))
        assertEquals(AuthState.Initializing, manager.state.value)
        assertNull(store.load())
    }

    @Test
    fun networkFailureOnLoginIsReported() = runTest {
        api.loginResult = ApiResult.NetworkError("boom")
        assertTrue((manager.login("a", "b") as LoginOutcome.Failure).message.contains("server"))
    }

    @Test
    fun restoreRefreshesAndRotatesStoredToken() = runTest {
        store.save(StoredSession("refresh1", user))
        manager.restore()
        assertEquals(AuthState.Authenticated(user), manager.state.value)
        assertEquals(listOf("refresh1"), api.refreshCalls)
        assertEquals("refresh2", store.load()?.refreshToken)
        assertEquals("access2", manager.currentAccessToken())
    }

    @Test
    fun restoreWithRevokedSessionClearsAndRequiresReauth() = runTest {
        store.save(StoredSession("refresh1", user))
        api.refreshResult = { ApiResult.Rejected(401, "revoked", unauthorized = true) }
        manager.restore()
        assertTrue(manager.state.value is AuthState.ReauthenticationRequired)
        assertNull(store.load())
        assertNull(manager.currentAccessToken())
        assertEquals(1, cleared)
    }

    @Test
    fun restoreOfflineKeepsUserSignedIn() = runTest {
        store.save(StoredSession("refresh1", user))
        api.refreshResult = { ApiResult.NetworkError("offline") }
        manager.restore()
        assertEquals(AuthState.Authenticated(user), manager.state.value)
        assertEquals("refresh1", store.load()?.refreshToken)
    }

    @Test
    fun restoreOnServerErrorDoesNotLogOut() = runTest {
        store.save(StoredSession("refresh1", user))
        api.refreshResult = { ApiResult.Rejected(503, "down", unauthorized = false) }
        manager.restore()
        assertEquals(AuthState.Authenticated(user), manager.state.value)
        assertEquals("refresh1", store.load()?.refreshToken)
    }

    @Test
    fun brokenSecureStorageFallsBackToLogin() = runTest {
        SessionManager(api, FailingStore()).also {
            it.restore()
            assertEquals(AuthState.Unauthenticated, it.state.value)
        }
    }

    @Test
    fun loginStillWorksWhenStorageFailsToSave() = runTest {
        val m = SessionManager(api, FailingStore())
        assertEquals(LoginOutcome.Success, m.login("a", "b"))
        assertEquals(AuthState.Authenticated(user), m.state.value)
    }

    @Test
    fun staleRefreshReusesTokenFromConcurrentWinner() = runTest {
        manager.login("a", "b") // access1
        // First caller refreshed after seeing "access1"; second caller still holds the stale "access1".
        assertEquals("access2", manager.refreshAccessToken("access1"))
        assertEquals("access2", manager.refreshAccessToken("access1"))
        assertEquals(1, api.refreshCalls.size)
    }

    @Test
    fun refreshRejectedInvalidatesSession() = runTest {
        manager.login("a", "b")
        api.refreshResult = { ApiResult.Rejected(401, "expired", unauthorized = true) }
        assertNull(manager.refreshAccessToken("access1"))
        assertTrue(manager.state.value is AuthState.ReauthenticationRequired)
        assertNull(store.load())
    }

    @Test
    fun refreshNetworkErrorKeepsSession() = runTest {
        manager.login("a", "b")
        api.refreshResult = { ApiResult.NetworkError("offline") }
        assertNull(manager.refreshAccessToken("access1"))
        assertEquals(AuthState.Authenticated(user), manager.state.value)
        assertEquals("refresh1", store.load()?.refreshToken)
    }

    @Test
    fun logoutRevokesServerSideAndClearsEverything() = runTest {
        manager.login("a", "b")
        assertTrue(manager.logout())
        assertEquals(listOf("refresh1"), api.logoutCalls)
        assertEquals(AuthState.Unauthenticated, manager.state.value)
        assertNull(store.load())
        assertNull(manager.currentAccessToken())
        assertEquals(1, cleared)
        assertNull(manager.refreshAccessToken(null)) // cannot reauthenticate with logged-out session
    }

    @Test
    fun unconfirmedLogoutExposesNoticeUntilNextLogin() = runTest {
        manager.login("a", "b")
        api.logoutResult = ApiResult.NetworkError("offline")
        assertFalse(manager.logout())
        assertTrue(manager.logoutNotice.value != null)
        manager.login("a", "b")
        assertNull(manager.logoutNotice.value)
    }

    @Test
    fun logoutClearsLocallyEvenWhenServerUnreachable() = runTest {
        manager.login("a", "b")
        api.logoutResult = ApiResult.NetworkError("offline")
        assertFalse(manager.logout())
        assertEquals(AuthState.Unauthenticated, manager.state.value)
        assertNull(store.load())
    }
}
