package dev.livin.nammaoorufood.admin

import kotlinx.serialization.Serializable

@Serializable
data class CustomerDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val phone: String?,
    val active: Boolean,
    val createdAt: String,
    val orderCount: Int,
)

@Serializable
data class CustomerPage(val items: List<CustomerDto>, val page: Int, val pageSize: Int, val total: Int)

@Serializable
data class ActiveRequest(val active: Boolean)

@Serializable
data class HotelSalesRow(
    val hotelId: Long,
    val hotelName: String,
    val ordersPlaced: Int,
    val completedOrders: Int,
    val rejectedOrders: Int,
    val sales: String,
)

@Serializable
data class HotelSalesReport(val from: String, val to: String, val rows: List<HotelSalesRow>)

@Serializable
data class PlatformOverview(
    val from: String,
    val to: String,
    val ordersPlaced: Int,
    val completedOrders: Int,
    val rejectedOrders: Int,
    val totalSales: String,
    val totalCustomers: Int,
    val activeCustomers: Int,
    val approvedHotels: Int,
    val pendingHotels: Int,
)
