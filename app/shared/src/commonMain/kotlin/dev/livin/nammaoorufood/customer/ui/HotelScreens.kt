package dev.livin.nammaoorufood.customer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livin.nammaoorufood.customer.cart.CartState
import dev.livin.nammaoorufood.customer.cart.Money
import dev.livin.nammaoorufood.customer.data.Load
import dev.livin.nammaoorufood.network.FoodItemDto
import dev.livin.nammaoorufood.network.HotelDto
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HotelListScreen(vm: HotelListViewModel, onOpenHotel: (HotelDto) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Hotels near you")
        OutlinedTextField(
            value = state.query,
            onValueChange = { vm.onIntent(HotelListIntent.QueryChanged(it)) },
            label = { Text("Search hotels") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.openOnly,
                onClick = { vm.onIntent(HotelListIntent.ToggleOpenOnly) },
                label = { Text("Open now") },
            )
        }
        when (val hotels = state.hotels) {
            Load.Loading -> CenteredLoading()
            is Load.Error -> ErrorState(hotels.message) { vm.onIntent(HotelListIntent.Refresh) }
            is Load.Success ->
                if (hotels.data.isEmpty()) EmptyState("No hotels found.")
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(hotels.data, key = { it.id }) { HotelCard(it) { onOpenHotel(it) } }
                }
        }
    }
}

@Composable
private fun HotelCard(hotel: HotelDto, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(hotel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OpenBadge(hotel.isOpen)
        }
        Text(hotel.address, style = MaterialTheme.typography.bodyMedium)
        hotel.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun OpenBadge(open: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (open) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (open) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(if (open) "● Open" else "○ Closed", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun MenuScreen(vm: MenuViewModel, onBack: () -> Unit, onOpenCart: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        when (val content = state.content) {
            Load.Loading -> { ScreenTitle("Menu", onBack); CenteredLoading() }
            is Load.Error -> { ScreenTitle("Menu", onBack); ErrorState(content.message) { vm.onIntent(MenuIntent.Reload) } }
            is Load.Success -> {
                val hotel = content.data.hotel
                ScreenTitle(hotel.name, onBack)
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OpenBadge(hotel.isOpen)
                    Spacer(Modifier.width(8.dp))
                    if (!hotel.isOpen) Text("Orders are not being accepted right now", style = MaterialTheme.typography.bodySmall)
                }
                val categories = content.data.menu.categories.sortedBy { it.sortOrder }
                val items = content.data.menu.items
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (items.isEmpty()) item { EmptyState("This hotel has no menu yet.") }
                    categories.forEach { category ->
                        val inCategory = items.filter { it.categoryId == category.id }
                        if (inCategory.isNotEmpty()) {
                            item(key = "c${category.id}") { Text(category.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                            items(inCategory, key = { it.id }) { MenuItemRow(it, state.cart.quantityOf(it.id), hotel.isOpen, vm::onIntent) }
                        }
                    }
                    val known = categories.map { it.id }.toSet()
                    val other = items.filter { it.categoryId == null || it.categoryId !in known }
                    if (other.isNotEmpty()) {
                        if (categories.isNotEmpty()) item(key = "other") { Text("Other", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                        items(other, key = { it.id }) { MenuItemRow(it, state.cart.quantityOf(it.id), hotel.isOpen, vm::onIntent) }
                    }
                }
            }
        }
        CartBar(state.cart, onOpenCart)
    }
    state.pendingConflict?.let {
        AlertDialog(
            onDismissRequest = { vm.onIntent(MenuIntent.DismissConflict) },
            title = { Text("Start a new order?") },
            text = { Text("Your cart has items from ${state.cart.hotelName}. An order can include only one hotel. Clear the cart and add ${it.name}?") },
            confirmButton = { TextButton(onClick = { vm.onIntent(MenuIntent.ConfirmReplaceCart) }) { Text("Clear and add") } },
            dismissButton = { TextButton(onClick = { vm.onIntent(MenuIntent.DismissConflict) }) { Text("Keep cart") } },
        )
    }
    state.message?.let {
        AlertDialog(
            onDismissRequest = { vm.onIntent(MenuIntent.DismissMessage) },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { vm.onIntent(MenuIntent.DismissMessage) }) { Text("OK") } },
        )
    }
}

@Composable
private fun MenuItemRow(item: FoodItemDto, quantity: Int, hotelOpen: Boolean, onIntent: (MenuIntent) -> Unit) {
    AppCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                item.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(Money.format(Money.parsePaise(item.price)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            when {
                !item.available -> Text("Unavailable", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                quantity == 0 -> Button(onClick = { onIntent(MenuIntent.Add(item)) }, enabled = hotelOpen) { Text("Add") }
                else -> QuantityStepper(quantity, { onIntent(MenuIntent.Decrement(item.id)) }, { onIntent(MenuIntent.Increment(item.id)) })
            }
        }
    }
}

@Composable
fun QuantityStepper(quantity: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onMinus, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("$quantity", Modifier.widthIn(min = 32.dp).padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = onPlus, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun CartBar(cart: CartState, onOpenCart: () -> Unit) {
    if (cart.isEmpty) return
    Button(
        onClick = onOpenCart,
        modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
    ) {
        Text("View cart · ${cart.itemCount} item${if (cart.itemCount == 1) "" else "s"} · ${Money.format(cart.totalPaise)}")
    }
}
