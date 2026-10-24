package dev.livin.nammaoorufood.network

import kotlinx.serialization.Serializable

@Serializable
enum class OrderStatus { PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP, COMPLETED, REJECTED, CANCELLED;

    val isTerminal get() = this == COMPLETED || this == REJECTED || this == CANCELLED
}

@Serializable
data class OperatingHourDto(val dayOfWeek: Int, val opensAt: String, val closesAt: String)

@Serializable
data class HotelDto(
    val id: Long,
    val name: String,
    val description: String? = null,
    val address: String,
    val phone: String? = null,
    val approvalStatus: String? = null,
    val acceptingOrders: Boolean,
    val isOpen: Boolean,
    val operatingHours: List<OperatingHourDto> = emptyList(),
)

@Serializable
data class HotelPage(val items: List<HotelDto>, val page: Int, val pageSize: Int, val total: Int)

@Serializable
data class CategoryDto(val id: Long, val name: String, val sortOrder: Int)

@Serializable
data class FoodItemDto(
    val id: Long,
    val categoryId: Long? = null,
    val name: String,
    val description: String? = null,
    val price: String,
    val available: Boolean,
)

@Serializable
data class MenuResponse(val categories: List<CategoryDto>, val items: List<FoodItemDto>)

@Serializable
data class OrderLineRequest(val foodItemId: Long, val quantity: Int)

@Serializable
data class PlaceOrderRequest(val hotelId: Long, val items: List<OrderLineRequest>, val note: String? = null)

@Serializable
data class OrderItemDto(val foodItemId: Long? = null, val name: String, val unitPrice: String, val quantity: Int, val lineTotal: String)

@Serializable
data class OrderDto(
    val id: Long,
    val hotelId: Long,
    val hotelName: String,
    val customerName: String,
    val status: OrderStatus,
    val total: String,
    val note: String? = null,
    val items: List<OrderItemDto>,
    val pickupToken: Int? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class OrderPage(val items: List<OrderDto>, val page: Int, val pageSize: Int, val total: Int)

@Serializable
data class NotificationDto(
    val id: Long,
    val orderId: Long? = null,
    val type: String,
    val title: String,
    val message: String,
    val read: Boolean,
    val createdAt: String,
)

@Serializable
data class NotificationPage(val items: List<NotificationDto>, val unreadCount: Int)
