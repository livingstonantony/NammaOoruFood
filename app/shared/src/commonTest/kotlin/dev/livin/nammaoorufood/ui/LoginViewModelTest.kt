package dev.livin.nammaoorufood.ui

import dev.livin.nammaoorufood.auth.*
import dev.livin.nammaoorufood.network.AuthResponse
import dev.livin.nammaoorufood.network.Role
import dev.livin.nammaoorufood.network.UserDto
import dev.livin.nammaoorufood.ui.login.LoginIntent
import dev.livin.nammaoorufood.ui.login.LoginViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val user = UserDto(1, "customer", "Customer", Role.CUSTOMER)
    private var logins = 0
    private var result: ApiResult<AuthResponse> = ApiResult.Success(AuthResponse("a", "r", 900, user))
    private val api = object : AuthApi {
        override suspend fun login(username: String, password: String): ApiResult<AuthResponse> { logins++; return result }
        override suspend fun refresh(refreshToken: String) = result
        override suspend fun logout(refreshToken: String): ApiResult<Unit> = ApiResult.Success(Unit)
    }
    private val session = SessionManager(api, InMemorySessionStore())

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun blankFieldsShowValidationWithoutCallingServer() = runTest {
        val vm = LoginViewModel(session)
        vm.onIntent(LoginIntent.Submit)
        assertNotNull(vm.state.value.usernameError)
        assertNotNull(vm.state.value.passwordError)
        assertEquals(0, logins)
    }

    @Test
    fun successfulLoginAuthenticatesAndClearsPassword() = runTest {
        val vm = LoginViewModel(session)
        vm.onIntent(LoginIntent.UsernameChanged("customer"))
        vm.onIntent(LoginIntent.PasswordChanged("pw"))
        vm.onIntent(LoginIntent.Submit)
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals("", vm.state.value.password)
        assertEquals(AuthState.Authenticated(user), session.state.value)
    }

    @Test
    fun failedLoginShowsErrorAndEditingClearsIt() = runTest {
        result = ApiResult.Rejected(401, "x", unauthorized = true)
        val vm = LoginViewModel(session)
        vm.onIntent(LoginIntent.UsernameChanged("u"))
        vm.onIntent(LoginIntent.PasswordChanged("p"))
        vm.onIntent(LoginIntent.Submit)
        advanceUntilIdle()
        assertEquals("Invalid username or password", vm.state.value.error)
        assertEquals("p", vm.state.value.password)
        vm.onIntent(LoginIntent.PasswordChanged("q"))
        assertNull(vm.state.value.error)
    }

    @Test
    fun duplicateSubmitsWhileLoadingAreIgnored() = runTest {
        val vm = LoginViewModel(session)
        vm.onIntent(LoginIntent.UsernameChanged("u"))
        vm.onIntent(LoginIntent.PasswordChanged("p"))
        vm.onIntent(LoginIntent.Submit)
        vm.onIntent(LoginIntent.Submit)
        advanceUntilIdle()
        assertEquals(1, logins)
    }

    @Test
    fun passwordVisibilityToggles() = runTest {
        val vm = LoginViewModel(session)
        vm.onIntent(LoginIntent.TogglePasswordVisibility)
        assertTrue(vm.state.value.passwordVisible)
    }
}
