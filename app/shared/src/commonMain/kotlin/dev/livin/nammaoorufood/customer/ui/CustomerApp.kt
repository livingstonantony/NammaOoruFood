package dev.livin.nammaoorufood.customer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.livin.nammaoorufood.auth.SessionManager
import dev.livin.nammaoorufood.network.NotificationDto
import dev.livin.nammaoorufood.network.UserDto
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds

enum class Tab(val title: String, val icon: String) {
    Home("Home", "🏠"),
    Orders("Orders", "🧾"),
    Profile("Profile", "👤"),
}

sealed interface Route {
    data class Menu(val hotelId: Long) : Route
    data object Cart : Route
    data class OrderDetail(val orderId: Long) : Route
}

class CustomerNavigator {
    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Route>()
    val top: Route? get() = stack.lastOrNull()

    fun push(route: Route) {
        stack.add(route)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun showOrder(id: Long) {
        stack.clear(); tab = Tab.Orders; stack.add(Route.OrderDetail(id))
    }
}

@Composable
fun CustomerApp(user: UserDto, session: SessionManager) {
    val nav = remember { CustomerNavigator() }
    val top = nav.top
    val notifications: NotificationsViewModel = koinViewModel(key = "${user.id}-notifications")
    val unread by notifications.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            notifications.onIntent(NotificationsIntent.Refresh)
            delay(
                POLL_MILLIS.milliseconds
            )
        }
    }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = top != null || nav.tab != Tab.Home, // You can toggle this dynamically
        onBackCompleted = {
            if (top != null) nav.pop() else nav.tab = Tab.Home
        }
    )

    Scaffold(
        bottomBar = {
            if (top == null) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = nav.tab == tab,
                            onClick = { nav.tab = tab },
                            icon = { Text(tab.icon, style = MaterialTheme.typography.titleMedium) },
                            label = {
                                Text(
                                    tab.title,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            unread.unread.firstOrNull()?.let { n ->
                NotificationBanner(
                    n,
                    onView = {
                        notifications.onIntent(NotificationsIntent.Dismiss(n.id)); n.orderId?.let(
                        nav::showOrder
                    )
                    },
                    onDismiss = { notifications.onIntent(NotificationsIntent.Dismiss(n.id)) },
                )
            }
            Box(Modifier.weight(1f)) {
                when (top) {
                    is Route.Menu -> MenuScreen(
                        vm = koinViewModel(key = "${user.id}-menu-${top.hotelId}") {
                            parametersOf(
                                top.hotelId
                            )
                        },
                        onBack = nav::pop,
                        onOpenCart = { nav.push(Route.Cart) },
                    )

                    Route.Cart -> CartScreen(
                        koinViewModel(key = "${user.id}-cart"),
                        onBack = nav::pop,
                        onOrderPlaced = nav::showOrder
                    )

                    is Route.OrderDetail -> OrderDetailScreen(
                        vm = koinViewModel(key = "${user.id}-order-${top.orderId}") {
                            parametersOf(
                                top.orderId
                            )
                        },
                        onBack = nav::pop,
                    )

                    null -> when (nav.tab) {
                        Tab.Home -> HotelListScreen(
                            vm = koinViewModel(key = "${user.id}-hotels"),
                            onOpenHotel = { nav.push(Route.Menu(it.id)) })

                        Tab.Orders -> OrdersScreen(
                            koinViewModel(key = "${user.id}-orders"),
                            onOpenOrder = { nav.push(Route.OrderDetail(it)) })

                        Tab.Profile -> ProfileScreen(user, session)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(user: UserDto, session: SessionManager) {
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Profile")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard {
                Text(user.displayName, style = MaterialTheme.typography.titleLarge)
                Text("@${user.username}", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(
                enabled = !loggingOut,
                onClick = { loggingOut = true; scope.launch { session.logout() } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Log out") }
        }
    }
}

@Composable
private fun NotificationBanner(n: NotificationDto, onView: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(n.title, style = MaterialTheme.typography.titleSmall)
                Text(n.message, style = MaterialTheme.typography.bodyMedium)
            }
            if (n.orderId != null) TextButton(onClick = onView) { Text("View") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
