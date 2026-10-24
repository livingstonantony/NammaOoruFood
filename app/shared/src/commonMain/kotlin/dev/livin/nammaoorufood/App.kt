package dev.livin.nammaoorufood

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livin.nammaoorufood.auth.AuthState
import dev.livin.nammaoorufood.auth.SessionManager
import dev.livin.nammaoorufood.di.appModule
import dev.livin.nammaoorufood.di.rememberPlatformServices
import dev.livin.nammaoorufood.customer.ui.CustomerApp
import dev.livin.nammaoorufood.network.Role
import dev.livin.nammaoorufood.owner.ui.OwnerApp
import dev.livin.nammaoorufood.admin.ui.AdminApp
import dev.livin.nammaoorufood.ui.HomeScreen
import dev.livin.nammaoorufood.ui.NammaTheme
import dev.livin.nammaoorufood.ui.SplashScreen
import dev.livin.nammaoorufood.ui.login.LoginScreen
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject

@Composable
fun App(variant: AppVariant = AppVariant.CUSTOMER) {
    val platform = rememberPlatformServices()
    KoinApplication(application = { modules(appModule(platform)) }) {
        NammaTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AuthGate(variant)
            }
        }
    }
}

/** Protected content is only composed while [AuthState.Authenticated]; session restore happens before anything else. */
@Composable
private fun AuthGate(variant: AppVariant) {
    val session = koinInject<SessionManager>()
    LaunchedEffect(session) { session.restore() }
    val state by session.state.collectAsStateWithLifecycle()
    val logoutNotice by session.logoutNotice.collectAsStateWithLifecycle()
    when (val s = state) {
        AuthState.Initializing -> SplashScreen()
        AuthState.Unauthenticated -> LoginScreen(notice = logoutNotice)
        is AuthState.ReauthenticationRequired -> LoginScreen(notice = s.reason)
        is AuthState.Authenticated ->
            if (s.user.role != variant.role) WrongAppScreen(variant, s.user.role, session)
            else when (s.user.role) {
                Role.CUSTOMER -> CustomerApp(s.user, session)
                Role.HOTEL_OWNER -> OwnerApp(s.user, session)
                Role.ADMIN -> AdminApp(s.user, session)
            }
    }
}

@Composable
private fun WrongAppScreen(variant: AppVariant, actual: Role, session: SessionManager) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().safeContentPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(variant.title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "This account is a ${actual.name.lowercase().replace('_', ' ')} account and cannot be used in this app.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { scope.launch { session.logout() } }) { Text("Sign out") }
    }
}
