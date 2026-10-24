package dev.livin.nammaoorufood.admin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livin.nammaoorufood.admin.data.AdminRepository
import dev.livin.nammaoorufood.auth.SessionManager
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.ui.*
import dev.livin.nammaoorufood.network.*
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

enum class AdminTab(val title: String) { Overview("Overview"), Hotels("Hotels"), Customers("Customers"), Orders("Orders") }

private val MAX_CONTENT_WIDTH = 960.dp

/** Wide screens get a centered content column; narrow screens use the full width. */
@Composable
fun AdminApp(user: UserDto, session: SessionManager) {
    var tab by remember { mutableStateOf(AdminTab.Overview) }
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().safeContentPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Namma Ooru Food · Admin", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Signed in as ${user.displayName}", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(enabled = !loggingOut, onClick = { loggingOut = true; scope.launch { session.logout() } }) { Text("Log out") }
            }
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                AdminTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) }) }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    AdminTab.Overview -> OverviewScreen(koinViewModel(key = "${user.id}-admin-overview"))
                    AdminTab.Hotels -> HotelsScreen(koinViewModel(key = "${user.id}-admin-hotels"))
                    AdminTab.Customers -> CustomersScreen(koinViewModel(key = "${user.id}-admin-customers"))
                    AdminTab.Orders -> AdminOrdersScreen(koinViewModel(key = "${user.id}-admin-orders"))
                }
            }
        }
    }
}

@Composable
private fun Pager(page: Int, pageSize: Int, total: Int, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onPage(page - 1) }, enabled = page > 1) { Text("← Previous") }
        Text("Page $page · $total total")
        TextButton(onClick = { onPage(page + 1) }, enabled = hasNextPage(page, pageSize, total)) { Text("Next →") }
    }
}

@Composable
private fun ErrorText(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
}

@Composable
private fun OverviewScreen(vm: AdminOverviewViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val o = state.overview) {
        Load.Loading -> CenteredLoading()
        is Load.Error -> ErrorState(o.message, vm::refresh)
        is Load.Success -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val d = o.data
            item {
                AppCard {
                    Text("Report period", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(state.from, { vm.setRange(it, state.to) }, label = { Text("From (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(state.to, { vm.setRange(state.from, it) }, label = { Text("To (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    state.rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = vm::applyRange, modifier = Modifier.padding(top = 8.dp)) { Text("Apply") }
                }
            }
            item {
                AppCard {
                    Text("Total sales (${d.from} to ${d.to})", style = MaterialTheme.typography.titleMedium)
                    Text("₹${d.totalSales}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric("Orders", d.ordersPlaced, Modifier.weight(1f))
                    Metric("Completed", d.completedOrders, Modifier.weight(1f))
                    Metric("Rejected", d.rejectedOrders, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric("Customers", d.totalCustomers, Modifier.weight(1f))
                    Metric("Active", d.activeCustomers, Modifier.weight(1f))
                    Metric("Hotels live", d.approvedHotels, Modifier.weight(1f))
                    Metric("Pending hotels", d.pendingHotels, Modifier.weight(1f))
                }
            }
            item { Text("Hotel-wise sales", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            when (val s = state.sales) {
                Load.Loading -> item { CenteredLoadingInline() }
                is Load.Error -> item { ErrorText(s.message) }
                is Load.Success ->
                    if (s.data.rows.isEmpty()) item { EmptyState("No sales in this period") }
                    else items(s.data.rows, key = { it.hotelId }) { r ->
                        AppCard {
                            Text(r.hotelName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Sales ₹${r.sales}", style = MaterialTheme.typography.bodyLarge)
                            Text("${r.ordersPlaced} placed · ${r.completedOrders} completed · ${r.rejectedOrders} rejected", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
            }
        }
    }
}

@Composable
private fun CenteredLoadingInline() = Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun Metric(label: String, value: Int, modifier: Modifier) {
    AppCard(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HotelsScreen(vm: AdminHotelsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            state.query, vm::setQuery, label = { Text("Search hotels") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        ErrorText(state.message)
        when (val h = state.hotels) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(h.message, vm::refresh)
            is Load.Success ->
                if (h.data.isEmpty()) EmptyState("No hotels found")
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(h.data, key = { it.id }) { hotel ->
                        val status = hotel.approvalStatus ?: "UNKNOWN"
                        val busy = hotel.id in state.busy
                        AppCard {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(hotel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(statusLabel(status), style = MaterialTheme.typography.labelLarge)
                            }
                            Text(hotel.address, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (status != "APPROVED") Button(enabled = !busy, onClick = { vm.setApproval(hotel.id, "APPROVED") }) { Text("Approve") }
                                if (status == "PENDING") OutlinedButton(enabled = !busy, onClick = { vm.setApproval(hotel.id, "REJECTED") }) { Text("Reject") }
                                if (status == "APPROVED") OutlinedButton(enabled = !busy, onClick = { vm.setApproval(hotel.id, "SUSPENDED") }) { Text("Suspend") }
                            }
                        }
                    }
                }
        }
    }
}

fun statusLabel(status: String) = when (status) {
    "APPROVED" -> "✔ Approved"
    "PENDING" -> "⏳ Pending"
    "REJECTED" -> "✖ Rejected"
    "SUSPENDED" -> "⏸ Suspended"
    else -> status
}

@Composable
private fun CustomersScreen(vm: AdminCustomersViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<CustomerDto?>(null) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            state.query, vm::setQuery, label = { Text("Search customers") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        ErrorText(state.message)
        when (val p = state.page) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(p.message, vm::refresh)
            is Load.Success -> Column(Modifier.fillMaxSize()) {
                if (p.data.items.isEmpty()) EmptyState("No customers found")
                else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(p.data.items, key = { it.id }) { c ->
                        AppCard {
                            Text(c.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("@${c.username} · ${c.orderCount} orders", style = MaterialTheme.typography.bodyMedium)
                            Text(if (c.active) "✔ Active" else "⛔ Deactivated", style = MaterialTheme.typography.labelLarge)
                            OutlinedButton(enabled = c.id !in state.busy, onClick = { if (c.active) confirm = c else vm.setActive(c.id, true) }) {
                                Text(if (c.active) "Deactivate" else "Reactivate")
                            }
                        }
                    }
                }
                Pager(p.data.page, p.data.pageSize, p.data.total, vm::goToPage)
            }
        }
    }
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Deactivate ${c.displayName}?") },
            text = { Text("They will be signed out and unable to log in until reactivated.") },
            confirmButton = { TextButton(onClick = { vm.setActive(c.id, false); confirm = null }) { Text("Deactivate") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AdminOrdersScreen(vm: AdminOrdersViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Status:", Modifier.align(Alignment.CenterVertically))
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }) { Text(state.status?.name ?: "All") }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem(text = { Text("All") }, onClick = { vm.setStatus(null); open = false })
                    OrderStatus.entries.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.setStatus(s); open = false }) }
                }
            }
        }
        when (val p = state.page) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(p.message, vm::refresh)
            is Load.Success -> Column(Modifier.fillMaxSize()) {
                if (p.data.items.isEmpty()) EmptyState("No orders found")
                else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(p.data.items, key = { it.id }) { o ->
                        AppCard {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("#${o.id} · ${o.hotelName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                StatusChip(o.status)
                            }
                            Text("${o.customerName} · ₹${o.total}${o.pickupToken?.let { " · Token #$it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                            Text(o.items.joinToString { "${it.quantity}× ${it.name}" }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Pager(p.data.page, p.data.pageSize, p.data.total, vm::goToPage)
            }
        }
    }
}
