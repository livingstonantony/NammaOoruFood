package dev.livin.nammaoorufood.customer.data

import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.path

interface ShopRepository {
    suspend fun hotels(query: String?, openOnly: Boolean): ApiResult<HotelPage>
    suspend fun hotel(id: Long): ApiResult<HotelDto>
    suspend fun menu(hotelId: Long): ApiResult<MenuResponse>
    suspend fun placeOrder(request: PlaceOrderRequest, idempotencyKey: String): ApiResult<OrderDto>
    suspend fun order(id: Long): ApiResult<OrderDto>
    suspend fun activeOrders(): ApiResult<List<OrderDto>>
    suspend fun orderHistory(): ApiResult<OrderPage>
    suspend fun cancelOrder(id: Long): ApiResult<OrderDto>
    suspend fun unreadNotifications(): ApiResult<NotificationPage>
    suspend fun markNotificationRead(id: Long): ApiResult<Unit>
}

class KtorShopRepository(private val client: HttpClient) : ShopRepository {
    override suspend fun hotels(query: String?, openOnly: Boolean) = client.apiCall<HotelPage> {
        url.path("hotels")
        parameter("q", query?.takeIf { it.isNotBlank() })
        parameter("openOnly", openOnly)
        parameter("pageSize", 50)
    }

    override suspend fun hotel(id: Long) = client.apiCall<HotelDto> { url.path("hotels/$id") }

    override suspend fun menu(hotelId: Long) = client.apiCall<MenuResponse> { url.path("hotels/$hotelId/menu") }

    override suspend fun placeOrder(request: PlaceOrderRequest, idempotencyKey: String) = client.apiCall<OrderDto> {
        method = HttpMethod.Post
        url.path("orders")
        header("Idempotency-Key", idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun order(id: Long) = client.apiCall<OrderDto> { url.path("orders/$id") }

    override suspend fun activeOrders() = client.apiCall<List<OrderDto>> { url.path("orders/active") }

    override suspend fun orderHistory() = client.apiCall<OrderPage> {
        url.path("orders")
        parameter("pageSize", 50)
    }

    override suspend fun cancelOrder(id: Long) = client.apiCall<OrderDto> {
        method = HttpMethod.Post
        url.path("orders/$id/cancel")
    }

    override suspend fun unreadNotifications() = client.apiCall<NotificationPage> {
        url.path("notifications")
        parameter("unread", true)
        parameter("limit", 20)
    }

    override suspend fun markNotificationRead(id: Long) = client.apiCall<Unit> {
        method = HttpMethod.Post
        url.path("notifications/$id/read")
    }
}
