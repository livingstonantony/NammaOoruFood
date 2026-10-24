package dev.livin.nammaoorufood.owner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.ui.*
import dev.livin.nammaoorufood.network.OrderDto
import dev.livin.nammaoorufood.network.OrderStatus
import dev.livin.nammaoorufood.network.OwnerDashboardDto
import kotlinx.coroutines.delay

private const val POLL_MS = 5_000L

@Composable
fun DashboardScreen(vm: OwnerDashboardViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { while (true) { delay(POLL_MS); vm.refresh() } }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Dashboard")
        when (val d = state.dashboard) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(d.message, vm::refresh)
            is Load.Success -> DashboardContent(d.data)
        }
    }
}

@Composable
private fun DashboardContent(d: OwnerDashboardDto) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Text("Today's sales", style = MaterialTheme.typography.titleMedium)
            Text("₹${d.todaySales}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Incoming", d.placed, Modifier.weight(1f))
            Stat("Preparing", d.accepted + d.preparing, Modifier.weight(1f))
            Stat("Ready", d.readyForPickup, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Completed", d.completedToday, Modifier.weight(1f))
            Stat("Rejected", d.rejectedToday, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier) {
    AppCard(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun OrdersBoardScreen(vm: OwnerOrdersViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { while (true) { delay(POLL_MS); vm.onIntent(OwnerOrdersIntent.Refresh) } }
    var confirmReject by remember { mutableStateOf<OrderDto?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Orders")
        PrimaryScrollableTabRow(selectedTabIndex = state.tab.ordinal, edgePadding = 8.dp) {
            OrderTab.entries.forEach { t ->
                Tab(selected = state.tab == t, onClick = { vm.onIntent(OwnerOrdersIntent.SelectTab(t)) }, text = { Text(t.title) })
            }
        }
        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }
        when (val o = state.orders) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(o.message) { vm.onIntent(OwnerOrdersIntent.Refresh) }
            is Load.Success ->
                if (o.data.isEmpty()) EmptyState("No ${state.tab.title.lowercase()} orders")
                else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(o.data, key = { it.id }) { order ->
                        OwnerOrderCard(order, order.id in state.busy,
                            onAccept = { vm.onIntent(OwnerOrdersIntent.Accept(order.id)) },
                            onReject = { confirmReject = order },
                            onAdvance = { vm.onIntent(OwnerOrdersIntent.Advance(order)) })
                    }
                }
        }
    }

    confirmReject?.let { order ->
        AlertDialog(
            onDismissRequest = { confirmReject = null },
            title = { Text("Reject order #${order.id}?") },
            text = { Text("The customer will be told the order was rejected. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.onIntent(OwnerOrdersIntent.Reject(order.id)); confirmReject = null }) { Text("Reject") }
            },
            dismissButton = { TextButton(onClick = { confirmReject = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun OwnerOrderCard(order: OrderDto, busy: Boolean, onAccept: () -> Unit, onReject: () -> Unit, onAdvance: () -> Unit) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                order.pickupToken?.let { "Token #$it" } ?: "Order #${order.id}",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            StatusChip(order.status)
        }
        Text(order.customerName, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        order.items.forEach { Text("${it.quantity} × ${it.name}", style = MaterialTheme.typography.bodyLarge) }
        order.note?.let { Text("Note: $it", style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))
        Text("Total ₹${order.total}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (order.status == OrderStatus.PLACED) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) { Text("Reject") }
                Button(onClick = onAccept, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) { Text("Accept") }
            }
        } else order.status.nextStep()?.let { (_, label) ->
            Button(onClick = onAdvance, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(label) }
        }
    }
}
