package dev.livin.nammaoorufood.network

import kotlinx.serialization.Serializable

@Serializable
data class OwnerDashboardDto(
    val todaySales: String,
    val placed: Int,
    val accepted: Int,
    val preparing: Int,
    val readyForPickup: Int,
    val completedToday: Int,
    val rejectedToday: Int,
)

@Serializable
data class HotelRequest(val name: String, val description: String? = null, val address: String, val phone: String? = null)

@Serializable
data class OperatingHoursRequest(val hours: List<OperatingHourDto>)

@Serializable
data class AcceptingOrdersRequest(val acceptingOrders: Boolean)

@Serializable
data class AvailabilityRequest(val available: Boolean)

@Serializable
data class CategoryRequest(val name: String, val sortOrder: Int = 0)

@Serializable
data class FoodItemRequest(
    val name: String,
    val description: String? = null,
    val price: String,
    val categoryId: Long? = null,
    val available: Boolean = true,
)

@Serializable
data class StatusUpdateRequest(val status: OrderStatus)
