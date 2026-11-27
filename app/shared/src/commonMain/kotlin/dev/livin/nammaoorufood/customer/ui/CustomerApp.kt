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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val title: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Orders("Orders", Icons.Default.ShoppingCart),
    Profile("Profile", Icons.Default.Person),
}

sealed interface Route {
    data class Menu(val hotelId: Long) : Route
    data object Cart : Route
    data class OrderDetail(val orderId: Long) : Route
    data object Notifications : Route
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
    val notifState by notifications.state.collectAsStateWithLifecycle()
    val unreadCount = notifState.unreadCount

    LaunchedEffect(Unit) {
        while (true) {
            notifications.onIntent(NotificationsIntent.Refresh)
            delay(POLL_MILLIS.milliseconds)
        }
    }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = top != null || nav.tab != Tab.Home,
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
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (top) {
                is Route.Menu -> MenuScreen(
                    vm = koinViewModel(key = "${user.id}-menu-${top.hotelId}") {
                        parametersOf(top.hotelId)
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
                        parametersOf(top.orderId)
                    },
                    onBack = nav::pop,
                )

                Route.Notifications -> NotificationsScreen(
                    vm = notifications,
                    onBack = nav::pop,
                    onOpenOrder = { nav.push(Route.OrderDetail(it)) },
                )

                null -> when (nav.tab) {
                    Tab.Home -> HotelListScreen(
                        vm = koinViewModel(key = "${user.id}-hotels"),
                        unreadCount = unreadCount,
                        onOpenNotifications = { nav.push(Route.Notifications) },
                        onOpenHotel = { nav.push(Route.Menu(it.id)) },
                    )

                    Tab.Orders -> OrdersScreen(
                        koinViewModel(key = "${user.id}-orders"),
                        onOpenOrder = { nav.push(Route.OrderDetail(it)) },
                    )

                    Tab.Profile -> ProfileScreen(user, session)
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
                user.username?.let { Text("@$it", style = MaterialTheme.typography.bodyMedium) }
            }
            OutlinedButton(
                enabled = !loggingOut,
                onClick = { loggingOut = true; scope.launch { session.logout() } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Log out") }
        }
    }
}
