package dev.livin.nammaoorufood.owner.data

import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.path

interface OwnerRepository {
    suspend fun hotels(): ApiResult<List<HotelDto>>
    suspend fun createHotel(request: HotelRequest): ApiResult<HotelDto>
    suspend fun updateHotel(id: Long, request: HotelRequest): ApiResult<HotelDto>
    suspend fun setHours(id: Long, hours: List<OperatingHourDto>): ApiResult<HotelDto>
    suspend fun setAcceptingOrders(id: Long, accepting: Boolean): ApiResult<HotelDto>
    suspend fun dashboard(): ApiResult<OwnerDashboardDto>
    suspend fun orders(hotelId: Long, statuses: Set<OrderStatus>): ApiResult<OrderPage>
    suspend fun accept(orderId: Long): ApiResult<OrderDto>
    suspend fun reject(orderId: Long): ApiResult<OrderDto>
    suspend fun updateStatus(orderId: Long, status: OrderStatus): ApiResult<OrderDto>
    suspend fun menu(hotelId: Long): ApiResult<MenuResponse>
    suspend fun createCategory(hotelId: Long, name: String): ApiResult<CategoryDto>
    suspend fun createItem(hotelId: Long, request: FoodItemRequest): ApiResult<FoodItemDto>
    suspend fun updateItem(hotelId: Long, itemId: Long, request: FoodItemRequest): ApiResult<FoodItemDto>
    suspend fun setItemAvailability(hotelId: Long, itemId: Long, available: Boolean): ApiResult<FoodItemDto>
    suspend fun deleteItem(hotelId: Long, itemId: Long): ApiResult<Unit>
}

class KtorOwnerRepository(private val client: HttpClient) : OwnerRepository {
    private suspend inline fun <reified R> send(method: HttpMethod, path: String, body: Any? = null) = client.apiCall<R> {
        this.method = method
        url.path(path)
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    override suspend fun createCategory(hotelId: Long, name: String) =
        send<CategoryDto>(HttpMethod.Post, "hotels/$hotelId/menu/categories", CategoryRequest(name))

    override suspend fun hotels() = send<List<HotelDto>>(HttpMethod.Get, "owner/hotels")
    override suspend fun createHotel(request: HotelRequest) = send<HotelDto>(HttpMethod.Post, "owner/hotels", request)
    override suspend fun updateHotel(id: Long, request: HotelRequest) = send<HotelDto>(HttpMethod.Put, "owner/hotels/$id", request)
    override suspend fun setHours(id: Long, hours: List<OperatingHourDto>) =
        send<HotelDto>(HttpMethod.Put, "owner/hotels/$id/hours", OperatingHoursRequest(hours))
    override suspend fun setAcceptingOrders(id: Long, accepting: Boolean) =
        send<HotelDto>(HttpMethod.Patch, "owner/hotels/$id/accepting-orders", AcceptingOrdersRequest(accepting))

    override suspend fun dashboard() = send<OwnerDashboardDto>(HttpMethod.Get, "owner/dashboard")

    override suspend fun orders(hotelId: Long, statuses: Set<OrderStatus>) = client.apiCall<OrderPage> {
        url.path("owner/orders")
        parameter("hotelId", hotelId)
        parameter("status", statuses.joinToString(",") { it.name })
        parameter("pageSize", 50)
    }

    override suspend fun accept(orderId: Long) = send<OrderDto>(HttpMethod.Post, "orders/$orderId/accept")
    override suspend fun reject(orderId: Long) = send<OrderDto>(HttpMethod.Post, "orders/$orderId/reject")
    override suspend fun updateStatus(orderId: Long, status: OrderStatus) =
        send<OrderDto>(HttpMethod.Patch, "orders/$orderId/status", StatusUpdateRequest(status))

    override suspend fun menu(hotelId: Long) = send<MenuResponse>(HttpMethod.Get, "hotels/$hotelId/menu")
    override suspend fun createItem(hotelId: Long, request: FoodItemRequest) =
        send<FoodItemDto>(HttpMethod.Post, "hotels/$hotelId/menu/items", request)
    override suspend fun updateItem(hotelId: Long, itemId: Long, request: FoodItemRequest) =
        send<FoodItemDto>(HttpMethod.Put, "hotels/$hotelId/menu/items/$itemId", request)
    override suspend fun setItemAvailability(hotelId: Long, itemId: Long, available: Boolean) =
        send<FoodItemDto>(HttpMethod.Patch, "hotels/$hotelId/menu/items/$itemId/availability", AvailabilityRequest(available))
    override suspend fun deleteItem(hotelId: Long, itemId: Long) = send<Unit>(HttpMethod.Delete, "hotels/$hotelId/menu/items/$itemId")
}
