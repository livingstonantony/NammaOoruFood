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
            ScreenTitle("Menu", modifier = Modifier.weight(1f))
            Button(onClick = { vm.onIntent(OwnerMenuIntent.DismissMessage); creating = true }, modifier = Modifier.padding(end = 12.dp).height(48.dp)) { Text("+ Add item") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        when (val m = state.menu) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(m.message) { vm.onIntent(OwnerMenuIntent.Refresh) }
            is Load.Success ->
                if (m.data.items.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("No items yet. Add your first dish.", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { vm.onIntent(OwnerMenuIntent.DismissMessage); creating = true }) { Text("+ Add item") }
                    }
                } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(m.data.items, key = { it.id }) { item ->
                        val busy = item.id in state.busy
                        val stock = item.quantityAvailable
                        AppCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("₹${item.price}", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        when {
                                            stock != null && stock <= 0 -> "🔴 Out of stock (0 left)"
                                            stock != null -> "📦 Stock: $stock left"
                                            item.available -> "🟢 Available (Unlimited)"
                                            else -> "🔴 Sold out"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                Switch(checked = item.available, enabled = !busy, onCheckedChange = { vm.onIntent(OwnerMenuIntent.ToggleAvailability(item)) })
                            }
                            Spacer(Modifier.height(8.dp))
                            StockControlRow(
                                item = item,
                                busy = busy,
                                onUpdateStock = { vm.onIntent(OwnerMenuIntent.UpdateStockQuantity(item, it)) },
                                onEdit = { vm.onIntent(OwnerMenuIntent.DismissMessage); editing = item },
                                onDelete = { deleting = item },
                            )
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
            confirmButton = { TextButton(onClick = { vm.onIntent(OwnerMenuIntent.Delete(item.id)); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ItemEditor(item: FoodItemDto?, state: OwnerMenuState, vm: OwnerMenuViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf(item?.name.orEmpty()) }
    var description by remember { mutableStateOf(item?.description.orEmpty()) }
    var price by remember { mutableStateOf(item?.price.orEmpty()) }
    var quantityAvailableStr by remember { mutableStateOf(item?.quantityAvailable?.toString().orEmpty()) }
    var trackInventory by remember(item?.id) { mutableStateOf(item?.quantityAvailable != null) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(trackInventory, {
                    trackInventory = it
                    if (!it) quantityAvailableStr = ""
                    else if (quantityAvailableStr.isEmpty()) quantityAvailableStr = "20"
                })
                Spacer(Modifier.width(12.dp))
                Text("Track inventory quantity")
            }
            if (trackInventory) {
                OutlinedTextField(
                    quantityAvailableStr, { quantityAvailableStr = it.filter { c -> c.isDigit() } },
                    label = { Text("Stock quantity (e.g. 50)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Owner can adjust quantity anytime using +, -, or direct input") },
                )
            }
            CategoryPicker(categories, categoryId) { categoryId = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newCategory, { newCategory = it }, label = { Text("New category") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(
                    enabled = !state.saving,
                    onClick = { vm.onIntent(OwnerMenuIntent.AddCategory(newCategory) { id -> categoryId = id; newCategory = "" }) },
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
                onClick = {
                    val targetQty = if (trackInventory) quantityAvailableStr.trim().toIntOrNull() else null
                    vm.onIntent(OwnerMenuIntent.Save(item?.id, name, description, price, categoryId, available, targetQty, onClose))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (state.saving) "Saving…" else "Save") }
        }
    }
}

@Composable
private fun StockControlRow(
    item: FoodItemDto,
    busy: Boolean,
    onUpdateStock: (Int?) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val stock = item.quantityAvailable
    var editingDirectly by remember(stock) { mutableStateOf(false) }
    var inputText by remember(stock) { mutableStateOf(stock?.toString().orEmpty()) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text("Stock:", style = MaterialTheme.typography.labelMedium)

            OutlinedButton(
                onClick = { onUpdateStock(((stock ?: 1) - 1).coerceAtLeast(0)) },
                enabled = !busy && stock != null && stock > 0,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("-", style = MaterialTheme.typography.titleMedium) }

            if (editingDirectly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it.filter { c -> c.isDigit() } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(64.dp),
                    )
                    TextButton(
                        onClick = {
                            val target = inputText.toIntOrNull()
                            onUpdateStock(target)
                            editingDirectly = false
                        },
                        enabled = !busy,
                    ) { Text("✓", fontWeight = FontWeight.Bold) }
                }
            } else {
                OutlinedButton(
                    onClick = {
                        if (!busy) {
                            inputText = stock?.toString().orEmpty()
                            editingDirectly = true
                        }
                    },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stock?.toString() ?: "∞",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            OutlinedButton(
                onClick = { onUpdateStock((stock ?: 0) + 1) },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("+", style = MaterialTheme.typography.titleMedium) }

            if (stock != null) {
                TextButton(onClick = { onUpdateStock(0) }, enabled = !busy) {
                    Text("0", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onUpdateStock(null) }, enabled = !busy) {
                    Text("∞", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                TextButton(onClick = { onUpdateStock(20) }, enabled = !busy) {
                    Text("+20", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onUpdateStock(50) }, enabled = !busy) {
                    Text("+50", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Row {
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
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
                        Switch(hotel.acceptingOrders, { vm.onIntent(OwnerHotelIntent.SetAccepting(hotel.id, it)) }, enabled = !saving)
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
                onClick = {
                    if (hotel == null) vm.onIntent(OwnerHotelIntent.CreateHotel(name, description, address, phone))
                    else vm.onIntent(OwnerHotelIntent.UpdateHotel(hotel.id, name, description, address, phone))
                },
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
                Button(enabled = !saving, onClick = { vm.onIntent(OwnerHotelIntent.SetHours(hotel.id, rows)) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save hours") }
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
