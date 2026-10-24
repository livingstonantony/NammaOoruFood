package dev.livin.nammaoorufood.customer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livin.nammaoorufood.customer.cart.Money
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.network.OrderDto
import dev.livin.nammaoorufood.network.OrderStatus
import kotlinx.coroutines.delay

internal const val POLL_MILLIS = 5_000L

@Composable
fun CartScreen(vm: CartViewModel, onBack: () -> Unit, onOrderPlaced: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.placedOrderId) {
        state.placedOrderId?.let { onOrderPlaced(it); vm.onIntent(CartIntent.OrderShown) }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Your cart", onBack)
        if (state.cart.isEmpty) {
            EmptyState("Your cart is empty.")
            return@Column
        }
        Text(state.cart.hotelName.orEmpty(), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.cart.lines, key = { it.itemId }) { line ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(line.name, style = MaterialTheme.typography.titleMedium)
                            Text("${Money.format(line.unitPricePaise)} each · ${Money.format(line.linePaise)}", style = MaterialTheme.typography.bodySmall)
                        }
                        QuantityStepper(line.quantity, { vm.onIntent(CartIntent.Decrement(line.itemId)) }, { vm.onIntent(CartIntent.Increment(line.itemId)) })
                    }
                    TextButton(onClick = { vm.onIntent(CartIntent.Remove(line.itemId)) }) { Text("Remove") }
                }
            }
            item {
                OutlinedTextField(
                    value = state.note,
                    onValueChange = { vm.onIntent(CartIntent.NoteChanged(it)) },
                    label = { Text("Note for the hotel (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.placing,
                )
            }
        }
        Column(Modifier.padding(16.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Estimated total", style = MaterialTheme.typography.titleMedium)
                Text(Money.format(state.cart.totalPaise), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text("Final amount is confirmed by the hotel's menu prices. Pay at pickup.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.onIntent(CartIntent.PlaceOrder) }, enabled = !state.placing, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (state.placing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Place order")
            }
        }
    }
}

@Composable
fun OrdersScreen(vm: OrdersViewModel, onOpenOrder: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val hasActive = state.active.isNotEmpty()
    LaunchedEffect(hasActive) {
        if (hasActive) while (true) { delay(POLL_MILLIS); vm.refresh() }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("My orders")
        when (val load = state.load) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(load.message) { vm.refresh() }
            is Load.Success ->
                if (state.active.isEmpty() && state.past.isEmpty()) EmptyState("You haven't placed any orders yet.")
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.active.isNotEmpty()) {
                        item { Text("In progress", style = MaterialTheme.typography.titleMedium) }
                        items(state.active, key = { "a${it.id}" }) { OrderRow(it, onOpenOrder) }
                    }
                    if (state.past.isNotEmpty()) {
                        item { Text("Past orders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                        items(state.past, key = { "p${it.id}" }) { OrderRow(it, onOpenOrder) }
                    }
                }
        }
    }
}

@Composable
private fun OrderRow(order: OrderDto, onOpen: (Long) -> Unit) {
    AppCard(onClick = { onOpen(order.id) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(order.hotelName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            order.pickupToken?.let { Text("Token #$it", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        }
        Text("Order #${order.id} · ${Money.format(Money.parsePaise(order.total))}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        StatusChip(order.status)
    }
}

@Composable
fun OrderDetailScreen(vm: OrderDetailViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val terminal = (state.order as? Load.Success)?.data?.status?.isTerminal == true
    LaunchedEffect(terminal) {
        if (!terminal) while (true) { delay(POLL_MILLIS); vm.refresh() }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Order details", onBack)
        when (val load = state.order) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(load.message) { vm.refresh() }
            is Load.Success -> OrderDetailContent(load.data, state.cancelling, state.actionError, vm::cancel)
        }
    }
}

@Composable
private fun OrderDetailContent(order: OrderDto, cancelling: Boolean, actionError: String?, onCancel: () -> Unit) {
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) CancelDialog(onConfirm = onCancel, onDismiss = { confirmCancel = false })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val ready = order.status == OrderStatus.READY_FOR_PICKUP
        if (ready) {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.large) {
                Text("Your order is ready! Please collect it from ${order.hotelName}.", Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        AppCard {
            Text(order.hotelName, style = MaterialTheme.typography.titleLarge)
            Text("Order #${order.id}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            StatusChip(order.status)
            Spacer(Modifier.height(4.dp))
            Text(statusHelp(order.status), style = MaterialTheme.typography.bodyMedium)
        }
        if (order.status == OrderStatus.PLACED) {
            OutlinedButton(enabled = !cancelling, onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (cancelling) "Cancelling…" else "Cancel order")
            }
        }
        actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        order.pickupToken?.let { token ->
            if (order.status != OrderStatus.REJECTED && order.status != OrderStatus.CANCELLED) {
                AppCard {
                    Text("Your pickup token", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text("#$token", Modifier.fillMaxWidth(), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                }
            }
        }
        AppCard {
            Text("Bill", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            order.items.forEach {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${it.quantity} × ${it.name}", Modifier.weight(1f))
                    Text(Money.format(Money.parsePaise(it.lineTotal)))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total", fontWeight = FontWeight.Bold)
                Text(Money.format(Money.parsePaise(order.total)), fontWeight = FontWeight.Bold)
            }
            order.note?.let { Text("Note: $it", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun statusHelp(status: OrderStatus) = when (status) {
    OrderStatus.PLACED -> "The hotel has your order and will confirm it shortly."
    OrderStatus.ACCEPTED -> "The hotel accepted your order. Show your token at pickup."
    OrderStatus.PREPARING -> "Your food is being prepared."
    OrderStatus.READY_FOR_PICKUP -> "Show your token at the counter."
    OrderStatus.COMPLETED -> "Thank you! This order has been picked up."
    OrderStatus.REJECTED -> "The hotel couldn't accept this order."
    OrderStatus.CANCELLED -> "You cancelled this order."
}

@Composable
private fun CancelDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Cancel this order?") },
    text = { Text("You can only cancel before the hotel accepts it.") },
    confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Cancel order") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Keep order") } },
)
