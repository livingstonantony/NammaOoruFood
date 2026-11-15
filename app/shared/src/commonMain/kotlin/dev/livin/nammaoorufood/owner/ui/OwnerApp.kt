package dev.livin.nammaoorufood.owner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livin.nammaoorufood.auth.SessionManager
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.ui.CenteredLoading
import dev.livin.nammaoorufood.customer.ui.ErrorState
import dev.livin.nammaoorufood.network.HotelDto
import dev.livin.nammaoorufood.network.UserDto
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

enum class OwnerTab(val title: String, val icon: String) {
    Dashboard("Dashboard", "📊"),
    Orders("Orders", "📋"),
    Menu("Menu", "🍽️"),
    Hotel("Hotel", "🏪"),
}

@Composable
fun OwnerApp(user: UserDto, session: SessionManager) {
    val hotelVm: OwnerHotelViewModel = koinViewModel(key = "${user.id}-owner-hotel")
    val state by hotelVm.state.collectAsStateWithLifecycle()
    when (val h = state.hotels) {
        Load.Loading -> CenteredLoading()
        is Load.Error -> ErrorState(h.message, hotelVm::refresh)
        is Load.Success -> {
            val hotel = state.hotel
            if (hotel == null) {
                HotelFormScreen(null, state.saving, state.message, hotelVm, session, Modifier.fillMaxSize())
            } else {
                OwnerShell(user, hotel, hotelVm, session)
            }
        }
    }
}

@Composable
private fun OwnerShell(user: UserDto, hotel: HotelDto, hotelVm: OwnerHotelViewModel, session: SessionManager) {
    var tab by remember { mutableStateOf(OwnerTab.Orders) }
    val state by hotelVm.state.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                OwnerTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.icon, style = MaterialTheme.typography.titleMedium) },
                        label = { Text(t.title, style = MaterialTheme.typography.labelMedium) },
                        alwaysShowLabel = true,
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (hotel.approvalStatus != null && hotel.approvalStatus != "APPROVED") {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Hotel status: ${hotel.approvalStatus}. Customers cannot order until an admin approves it.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    OwnerTab.Dashboard -> DashboardScreen(koinViewModel(key = "${user.id}-owner-dash"))
                    OwnerTab.Orders -> OrdersBoardScreen(koinViewModel(key = "${user.id}-owner-orders-${hotel.id}") { parametersOf(hotel.id) })
                    OwnerTab.Menu -> OwnerMenuScreen(koinViewModel(key = "${user.id}-owner-menu-${hotel.id}") { parametersOf(hotel.id) })
                    OwnerTab.Hotel -> HotelFormScreen(hotel, state.saving, state.message, hotelVm, session, Modifier)
                }
            }
        }
    }
}
