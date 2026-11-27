package dev.livin.nammaoorufood.miniapp.pages

import androidx.compose.runtime.*
import com.varabyte.kobweb.core.Page
import dev.livin.nammaoorufood.miniapp.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.*

@Page
@Composable
fun HomePage() {
    val scope = rememberCoroutineScope()
    val mockMode = remember { isLocalMockMode() }
    var api by remember { mutableStateOf<CustomerClient?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var hotels by remember { mutableStateOf<List<Hotel>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var total by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Hotel?>(null) }
    var menu by remember { mutableStateOf<List<FoodItem>>(emptyList()) }
    var quantities by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var note by remember { mutableStateOf("") }
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var showOrders by remember { mutableStateOf(false) }

    suspend fun loadHotels(client: CustomerClient, newPage: Int) {
        val result = client.hotels(newPage)
        hotels = result.items
        page = result.page
        total = result.total
    }

    suspend fun showError(block: suspend () -> Unit) {
        try {
            error = null
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (e is ApiException) e.message else "Could not reach the server. Please try again."
        }
    }

    LaunchedEffect(Unit) {
        prepareTelegram()
        showError {
            val client = if (mockMode) MockCustomerClient() else CustomerApi.connect(telegramInitData())
            api = client
            if (!mockMode) readPendingOrder(client.customerId)?.let { pending ->
                selected = pending.hotel
                menu = pending.menu
                quantities = pending.request.items.associate { it.foodItemId to it.quantity }
                note = pending.request.note.orEmpty()
                pendingKey = pending.key
                notice = "An order is awaiting confirmation. Retry it or check My orders."
            }
            loadHotels(client, 1)
            orders = client.orders().items
        }
        loading = false
    }

    LaunchedEffect(api) {
        val client = api ?: return@LaunchedEffect
        while (true) {
            delay(5000)
            try {
                orders = client.orders().items
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Could not refresh orders. Check your connection."
            }
        }
    }

    Main {
        Div(attrs = { classes("header") }) {
            Div {
                Div(attrs = { classes("brand") }) { Text("Namma Ooru Food") }
                H1 { Text(if (showOrders) "Your orders" else selected?.name ?: "Find your food") }
                P(attrs = { classes("muted") }) { Text("Local favourites, ready for pickup.") }
            }
        }
        if (mockMode) Div(attrs = { classes("notice"); attr("role", "status") }) {
            Text("Mock mode: sample data only. Orders are not sent and are cleared on reload.")
        }
        if (error != null) Div(attrs = { classes("error"); attr("role", "alert") }) { Text(error!!) }
        if (notice != null) Div(attrs = { classes("notice"); attr("role", "status") }) { Text(notice!!) }
        if (loading) {
            P(attrs = { classes("muted") }) { Text("Connecting to Telegram…") }
        } else if (api == null) {
            P(attrs = { classes("muted") }) { Text("Please reopen the Mini App from your bot and try again.") }
        } else {
            val client = api!!
            Div(attrs = { classes("tabs") }) {
                Button(attrs = {
                    classes("button")
                    if (showOrders) classes("secondary")
                    onClick { showOrders = false; notice = null }
                }) { Text("Hotels") }
                Button(attrs = {
                    classes("button")
                    if (!showOrders) classes("secondary")
                    onClick {
                        showOrders = true
                        notice = null
                        scope.launch { showError { orders = client.orders().items } }
                    }
                }) { Text("My orders") }
            }
            if (showOrders) {
                P(attrs = { classes("muted") }) {
                    Text(if (mockMode) "Sample orders are kept until this page is reloaded." else "Recent orders update every 5 seconds. Start the bot chat for status messages.")
                }
                if (orders.isEmpty()) Div(attrs = { classes("card") }) { Text("No orders yet.") }
                orders.forEach { order ->
                    Div(attrs = { classes("card", "stack") }) {
                        Div(attrs = { classes("row") }) {
                            H3 { Text("${order.hotelName} · #${order.id}") }
                            Span(attrs = { classes("badge") }) { Text(order.status.replace('_', ' ')) }
                        }
                        P(attrs = { classes("muted") }) { Text("₹${order.total} · Pay at pickup") }
                        order.pickupToken?.let { token -> P { Text("Pickup token: $token") } }
                        if (order.status == "PLACED") Button(attrs = {
                            classes("button", "danger")
                            if (busy) disabled()
                            onClick {
                                busy = true
                                scope.launch {
                                    showError {
                                        client.cancel(order.id)
                                        orders = client.orders().items
                                        notice = "Order #${order.id} cancelled."
                                    }
                                    busy = false
                                }
                            }
                        }) { Text("Cancel order") }
                    }
                }
            } else if (selected == null) {
                Div(attrs = { classes("section-title") }) {
                    H2 { Text("Open hotels") }
                    Button(attrs = {
                        classes("button", "secondary")
                        if (busy) disabled()
                        onClick {
                            busy = true
                            scope.launch { showError { loadHotels(client, page) }; busy = false }
                        }
                    }) { Text("Refresh") }
                }
                if (hotels.isEmpty()) P(attrs = { classes("card", "muted") }) { Text("No hotels are open right now.") }
                hotels.forEach { hotel ->
                    Div(attrs = { classes("card", "stack") }) {
                        Div(attrs = { classes("row") }) {
                            H3 { Text(hotel.name) }
                            Span(attrs = { classes("badge") }) { Text("Open") }
                        }
                        P(attrs = { classes("muted") }) { Text(hotel.address) }
                        hotel.description?.takeIf { it.isNotBlank() }?.let { P(attrs = { classes("muted") }) { Text(it) } }
                        Button(attrs = {
                            classes("button", "secondary")
                            if (busy) disabled()
                            onClick {
                                busy = true
                                scope.launch {
                                    showError { menu = client.menu(hotel.id).items; selected = hotel; quantities = emptyMap(); note = "" }
                                    busy = false
                                }
                            }
                        }) { Text("View menu") }
                    }
                }
                Div(attrs = { classes("row") }) {
                    Button(attrs = {
                        classes("button", "secondary")
                        if (page <= 1 || busy) disabled()
                        onClick { busy = true; scope.launch { showError { loadHotels(client, page - 1) }; busy = false } }
                    }) { Text("Previous") }
                    Span(attrs = { classes("muted") }) { Text("Page $page") }
                    Button(attrs = {
                        classes("button", "secondary")
                        if (page * 20 >= total || busy) disabled()
                        onClick { busy = true; scope.launch { showError { loadHotels(client, page + 1) }; busy = false } }
                    }) { Text("Next") }
                }
            } else {
                val hotel = selected!!
                Button(attrs = {
                    classes("button", "secondary")
                    if (busy || pendingKey != null) disabled()
                    onClick { selected = null; quantities = emptyMap(); notice = null }
                }) { Text("← All hotels") }
                Div(attrs = { classes("section-title") }) { H2 { Text("Menu") } }
                if (menu.isEmpty()) P(attrs = { classes("card", "muted") }) { Text("No items are available right now.") }
                menu.forEach { item ->
                    val quantity = quantities[item.id] ?: 0
                    Div(attrs = { classes("card", "row") }) {
                        Div(attrs = { classes("stack") }) {
                            H3 { Text(item.name) }
                            item.description?.let { P(attrs = { classes("muted") }) { Text(it) } }
                            Span(attrs = { classes("price") }) { Text("₹${item.price}") }
                        }
                        Div(attrs = { classes("counter") }) {
                            Button(attrs = {
                                classes("button", "secondary")
                                if (quantity == 0 || busy || pendingKey != null) disabled()
                                onClick { quantities = quantities + (item.id to quantity - 1) }
                            }) { Text("−") }
                            Span { Text("$quantity") }
                            Button(attrs = {
                                classes("button", "secondary")
                                if (busy || pendingKey != null || quantity >= minOf(item.quantityAvailable ?: 20, 20) ||
                                    (quantity == 0 && quantities.count { it.value > 0 } >= 30)) disabled()
                                onClick { quantities = quantities + (item.id to quantity + 1) }
                            }) { Text("+") }
                        }
                    }
                }
                val lines = quantities.filterValues { it > 0 }.map { OrderLine(it.key, it.value) }
                if (lines.isNotEmpty()) {
                    Div(attrs = { classes("card", "stack") }) {
                        H3 { Text("Your order") }
                        P(attrs = { classes("muted") }) { Text("${lines.sumOf { it.quantity }} items · Pay at pickup") }
                        P(attrs = { classes("price") }) { Text("Estimated total: ₹${estimatedTotal(menu, quantities)}") }
                        TextInput(attrs = {
                            attr("placeholder", "Note for the hotel (optional)")
                            attr("aria-label", "Note for the hotel")
                            value(note)
                            attr("maxlength", "255")
                            if (busy || pendingKey != null) disabled()
                            onInput { note = it.value }
                        })
                        Small { Text("The hotel confirms availability and the final total when you place the order.") }
                        if (pendingKey != null) Small { Text("The result is uncertain. Retry this same order before changing it; you can also check My orders.") }
                        Button(attrs = {
                            classes("button", "wide")
                            if (busy) disabled()
                            onClick {
                                busy = true
                                scope.launch {
                                    try {
                                        error = null
                                        val key = pendingKey ?: newOrderKey()
                                        val request = PlaceOrder(hotel.id, lines, note.trim().ifEmpty { null })
                                        if (!mockMode) savePendingOrder(PendingOrder(client.customerId, hotel, menu, request, key))
                                        pendingKey = key
                                        val order = client.place(request, key)
                                        if (!mockMode) clearPendingOrder()
                                        pendingKey = null
                                        quantities = emptyMap()
                                        selected = null
                                        notice = "Order #${order.id} placed. Total: ₹${order.total}. Pay at pickup."
                                        showOrders = true
                                        showError { orders = client.orders().items }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (e is ApiException && e.status in 400..499 && e.status != 408) {
                                            if (!mockMode) clearPendingOrder()
                                            pendingKey = null
                                        }
                                        error = if (e is ApiException) e.message else "Could not confirm your order. Retry the same order."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }) { Text(if (pendingKey == null) "Place order" else "Retry order") }
                    }
                }
            }
        }
    }
}
