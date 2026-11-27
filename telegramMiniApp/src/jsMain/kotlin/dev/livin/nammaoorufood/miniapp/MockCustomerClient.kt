package dev.livin.nammaoorufood.miniapp

import kotlinx.browser.window

fun isMockMode(hostname: String, search: String): Boolean {
    if (hostname != "localhost" && hostname != "127.0.0.1" && hostname != "pulsate-shaded-crust.ngrok-free.dev") return false
    val params = js("new URLSearchParams(search)")
    return params.get("mock") == "true"
}

fun isLocalMockMode(): Boolean = isMockMode(window.location.hostname, window.location.search)

class MockCustomerClient : CustomerClient {
    override val customerId = -1L
    private val hotels = listOf(
        Hotel(1, "Anandha Bhavan", "South Indian favourites", "Main Street", true),
        Hotel(2, "Village Kitchen", "Freshly prepared local meals", "Market Road", true),
    )
    private val menus = mapOf(
        1L to listOf(
            FoodItem(101, "Idli", "Served with chutney and sambar", "40.00", 20),
            FoodItem(102, "Dosa", "Crisp dosa with chutney", "60.00", 20),
            FoodItem(103, "Filter coffee", price = "25.00", quantityAvailable = 20),
        ),
        2L to listOf(
            FoodItem(201, "Vegetable meals", "Rice, vegetables and sambar", "90.00", 20),
            FoodItem(202, "Lemon rice", price = "55.00", quantityAvailable = 20),
        ),
    )
    private val placedOrders = mutableListOf<Order>()
    private val requests = mutableMapOf<String, Pair<PlaceOrder, Order>>()
    private var nextOrderId = 1L

    override suspend fun hotels(page: Int): HotelPage {
        if (page < 1) throw ApiException(400, "Invalid page.")
        return HotelPage(hotels.drop((page - 1) * 20).take(20), page, 20, hotels.size)
    }

    override suspend fun menu(id: Long): Menu =
        Menu(menus[id] ?: throw ApiException(404, "Hotel not found."))

    override suspend fun orders(): OrderPage = OrderPage(placedOrders.reversed(), 1, 50, placedOrders.size)

    override suspend fun place(order: PlaceOrder, key: String): Order {
        requests[key]?.let { (previous, result) ->
            if (previous != order) throw ApiException(409, "This order key was already used.")
            return result
        }
        val hotel = hotels.firstOrNull { it.id == order.hotelId }
            ?: throw ApiException(404, "Hotel not found.")
        val items = menus.getValue(hotel.id)
        if (order.items.isEmpty() || order.items.any { line ->
                line.quantity !in 1..20 || items.none { it.id == line.foodItemId }
            } || order.items.map { it.foodItemId }.distinct().size != order.items.size) {
            throw ApiException(400, "Invalid order items.")
        }
        val total = estimatedTotal(items, order.items.associate { it.foodItemId to it.quantity })
        val result = Order(nextOrderId++, hotel.name, "PLACED", total, null)
        placedOrders.add(result)
        requests[key] = order to result
        return result
    }

    override suspend fun cancel(id: Long): Order {
        val index = placedOrders.indexOfFirst { it.id == id }
        if (index < 0) throw ApiException(404, "Order not found.")
        val order = placedOrders[index]
        if (order.status != "PLACED") throw ApiException(409, "This order cannot be cancelled.")
        return order.copy(status = "CANCELLED").also { placedOrders[index] = it }
    }
}
