package dev.livin.nammaoorufood.customer

import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.customer.data.ShopRepository
import dev.livin.nammaoorufood.network.*

fun food(id: Long, price: String = "15.00", available: Boolean = true) =
    FoodItemDto(id, null, "Item$id", null, price, available)

fun aHotel(id: Long, open: Boolean = true) =
    HotelDto(id, "Hotel$id", null, "Main St", null, acceptingOrders = true, isOpen = open)

fun anOrder(id: Long, status: OrderStatus = OrderStatus.PLACED, token: Int? = null) =
    OrderDto(id, 1, "Hotel1", "Cust", status, "30.00", null, emptyList(), token, "t", "t")

class FakeShop : ShopRepository {
    var hotels: ApiResult<HotelPage> = ApiResult.Success(HotelPage(listOf(aHotel(1), aHotel(2, open = false)), 1, 50, 2))
    var menu: ApiResult<MenuResponse> = ApiResult.Success(MenuResponse(emptyList(), listOf(food(1), food(2))))
    var place: ApiResult<OrderDto> = ApiResult.Success(anOrder(99))
    var active: ApiResult<List<OrderDto>> = ApiResult.Success(emptyList())
    var history: ApiResult<OrderPage> = ApiResult.Success(OrderPage(emptyList(), 1, 50, 0))
    var single: ApiResult<OrderDto> = ApiResult.Success(anOrder(1))
    val hotelQueries = mutableListOf<Pair<String?, Boolean>>()
    val placed = mutableListOf<Pair<PlaceOrderRequest, String>>()

    override suspend fun hotels(query: String?, openOnly: Boolean): ApiResult<HotelPage> {
        hotelQueries += query to openOnly
        return hotels
    }
    override suspend fun hotel(id: Long): ApiResult<HotelDto> = ApiResult.Success(aHotel(id))
    override suspend fun menu(hotelId: Long) = menu
    override suspend fun placeOrder(request: PlaceOrderRequest, idempotencyKey: String): ApiResult<OrderDto> {
        placed += request to idempotencyKey
        return place
    }
    override suspend fun order(id: Long) = single
    override suspend fun activeOrders() = active
    override suspend fun orderHistory() = history
    var cancel: ApiResult<OrderDto> = ApiResult.Success(anOrder(1, OrderStatus.CANCELLED))
    var notifications: ApiResult<NotificationPage> = ApiResult.Success(NotificationPage(emptyList(), 0))
    val read = mutableListOf<Long>()
    var cancelCalls = 0
    override suspend fun cancelOrder(id: Long): ApiResult<OrderDto> { cancelCalls++; return cancel }
    override suspend fun unreadNotifications() = notifications
    override suspend fun markNotificationRead(id: Long): ApiResult<Unit> { read += id; return ApiResult.Success(Unit) }
}
