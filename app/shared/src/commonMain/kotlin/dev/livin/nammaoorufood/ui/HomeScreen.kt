package dev.livin.nammaoorufood.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.livin.nammaoorufood.auth.SessionManager
import dev.livin.nammaoorufood.network.UserDto
import kotlinx.coroutines.launch

/** Placeholder authenticated landing screen until the role-specific apps are built. */
@Composable
fun HomeScreen(user: UserDto, session: SessionManager) {
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().safeContentPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome, ${user.displayName}", style = MaterialTheme.typography.headlineSmall)
        Text("Signed in as ${user.role.name.replace('_', ' ')}", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            enabled = !loggingOut,
            onClick = { loggingOut = true; scope.launch { session.logout() } },
        ) { Text("Log out") }
    }
}
