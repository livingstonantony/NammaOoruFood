package dev.livin.nammaoorufood.owner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livin.nammaoorufood.auth.SessionManager
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.customer.ui.*
import dev.livin.nammaoorufood.network.CategoryDto
import dev.livin.nammaoorufood.network.FoodItemDto
import dev.livin.nammaoorufood.network.HotelDto
import kotlinx.coroutines.launch

@Composable
fun OwnerMenuScreen(vm: OwnerMenuViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<FoodItemDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FoodItemDto?>(null) }

    if (creating || editing != null) {
        ItemEditor(editing, state, vm, onClose = { creating = false; editing = null })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            ScreenTitle("Menu")
            Button(onClick = { vm.dismissMessage(); creating = true }, modifier = Modifier.padding(end = 12.dp).height(48.dp)) { Text("+ Add item") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        when (val m = state.menu) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(m.message, vm::refresh)
            is Load.Success ->
                if (m.data.items.isEmpty()) EmptyState("No items yet. Add your first dish.")
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(m.data.items, key = { it.id }) { item ->
                        AppCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("₹${item.price}", style = MaterialTheme.typography.bodyLarge)
                                    Text(if (item.available) "Available" else "Sold out", style = MaterialTheme.typography.labelLarge)
                                }
                                Switch(checked = item.available, enabled = item.id !in state.busy, onCheckedChange = { vm.toggleAvailability(item) })
                            }
                            Row {
                                TextButton(onClick = { vm.dismissMessage(); editing = item }) { Text("Edit") }
                                TextButton(onClick = { deleting = item }, enabled = item.id !in state.busy) { Text("Delete") }
                            }
                        }
                    }
                }
        }
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("Past orders keep their original item names and prices.") },
            confirmButton = { TextButton(onClick = { vm.delete(item.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ItemEditor(item: FoodItemDto?, state: OwnerMenuState, vm: OwnerMenuViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf(item?.name.orEmpty()) }
    var description by remember { mutableStateOf(item?.description.orEmpty()) }
    var price by remember { mutableStateOf(item?.price.orEmpty()) }
    var available by remember { mutableStateOf(item?.available ?: true) }
    var categoryId by remember { mutableStateOf(item?.categoryId) }
    var newCategory by remember { mutableStateOf("") }
    val categories = (state.menu as? Load.Success)?.data?.categories.orEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(if (item == null) "Add item" else "Edit item", onBack = onClose)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Item name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                price, { price = it }, label = { Text("Price (₹)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            CategoryPicker(categories, categoryId) { categoryId = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newCategory, { newCategory = it }, label = { Text("New category") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(
                    enabled = !state.saving,
                    onClick = { vm.addCategory(newCategory) { id -> categoryId = id; newCategory = "" } },
                ) { Text("Add") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(available, { available = it })
                Spacer(Modifier.width(12.dp))
                Text("Available for ordering")
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !state.saving,
                onClick = { vm.save(item?.id, name, description, price, categoryId, available, onClose) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (state.saving) "Saving…" else "Save") }
        }
    }
}

@Composable
fun HotelFormScreen(
    hotel: HotelDto?,
    saving: Boolean,
    message: String?,
    vm: OwnerHotelViewModel,
    session: SessionManager,
    modifier: Modifier,
) {
    var name by remember(hotel?.id) { mutableStateOf(hotel?.name.orEmpty()) }
    var description by remember(hotel?.id) { mutableStateOf(hotel?.description.orEmpty()) }
    var address by remember(hotel?.id) { mutableStateOf(hotel?.address.orEmpty()) }
    var phone by remember(hotel?.id) { mutableStateOf(hotel?.phone.orEmpty()) }
    var rows by remember(hotel?.id, hotel?.operatingHours) { mutableStateOf(hoursRowsOf(hotel?.operatingHours.orEmpty())) }
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(if (hotel == null) "Set up your hotel" else "Hotel")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hotel == null) Text("Add your hotel details. An admin must approve it before customers can order.")
            if (hotel != null) {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Accepting orders", style = MaterialTheme.typography.titleMedium)
                            Text(if (hotel.isOpen) "Open now" else "Not open to customers right now", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(hotel.acceptingOrders, { vm.setAccepting(hotel.id, it) }, enabled = !saving)
                    }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Hotel name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            Button(
                enabled = !saving,
                onClick = { if (hotel == null) vm.createHotel(name, description, address, phone) else vm.updateHotel(hotel.id, name, description, address, phone) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (saving) "Saving…" else if (hotel == null) "Create hotel" else "Save details") }

            if (hotel != null) {
                Text("Operating hours", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("24-hour time, e.g. 18:00 to 23:00. Leave both blank for a closed day.", style = MaterialTheme.typography.bodyMedium)
                rows.forEachIndexed { i, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(DAY_NAMES[i].take(3), Modifier.width(40.dp))
                        OutlinedTextField(row.opens, { v -> rows = rows.toMutableList().also { it[i] = row.copy(opens = v) } },
                            label = { Text("Opens") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(row.closes, { v -> rows = rows.toMutableList().also { it[i] = row.copy(closes = v) } },
                            label = { Text("Closes") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                Button(enabled = !saving, onClick = { vm.setHours(hotel.id, rows) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save hours") }
            }
            message?.let {
                Text(it, color = if (it == "Saved") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                enabled = !loggingOut,
                onClick = { loggingOut = true; scope.launch { session.logout() } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Log out") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(categories: List<CategoryDto>, selected: Long?, onSelect: (Long?) -> Unit) {
    Text("Category", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == null, { onSelect(null) }, label = { Text("None") })
        categories.forEach { c -> FilterChip(selected == c.id, { onSelect(c.id) }, label = { Text(c.name) }) }
    }
}
