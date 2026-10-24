package dev.livin.nammaoorufood.order

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant

enum class OrderStatus { PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP, COMPLETED, REJECTED, CANCELLED }

// PLACED -> ACCEPTED | REJECTED | CANCELLED (customer only); ACCEPTED -> PREPARING -> READY_FOR_PICKUP -> COMPLETED. COMPLETED, REJECTED and CANCELLED are terminal.
object OrderStateMachine {
    private val allowed = mapOf(
        OrderStatus.PLACED to setOf(OrderStatus.ACCEPTED, OrderStatus.REJECTED, OrderStatus.CANCELLED),
        OrderStatus.ACCEPTED to setOf(OrderStatus.PREPARING),
        OrderStatus.PREPARING to setOf(OrderStatus.READY_FOR_PICKUP),
        OrderStatus.READY_FOR_PICKUP to setOf(OrderStatus.COMPLETED),
    )

    fun canTransition(from: OrderStatus, to: OrderStatus) = to in allowed[from].orEmpty()
}

data class OrderItem(val foodItemId: Long?, val name: String, val unitPrice: BigDecimal, val quantity: Int, val lineTotal: BigDecimal)

data class Order(
    val id: Long,
    val customerId: Long,
    val customerName: String,
    val hotelId: Long,
    val hotelName: String,
    val status: OrderStatus,
    val total: BigDecimal,
    val note: String?,
    val items: List<OrderItem>,
    val pickupToken: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NewOrderItem(val foodItemId: Long, val name: String, val unitPrice: BigDecimal, val quantity: Int) {
    val lineTotal: BigDecimal get() = unitPrice.multiply(BigDecimal(quantity))
}

@Serializable
data class OrderLineRequest(val foodItemId: Long, val quantity: Int)

// Prices are deliberately absent: the server always prices from the current menu.
@Serializable
data class PlaceOrderRequest(val hotelId: Long, val items: List<OrderLineRequest>, val note: String? = null)

@Serializable
data class OrderItemDto(val foodItemId: Long?, val name: String, val unitPrice: String, val quantity: Int, val lineTotal: String)

@Serializable
data class OrderDto(
    val id: Long,
    val hotelId: Long,
    val hotelName: String,
    val customerName: String,
    val status: OrderStatus,
    val total: String,
    val note: String?,
    val items: List<OrderItemDto>,
    val pickupToken: Int?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class OrderPage(val items: List<OrderDto>, val page: Int, val pageSize: Int, val total: Int)

fun Order.toDto() = OrderDto(
    id, hotelId, hotelName, customerName, status, total.setScale(2).toPlainString(), note,
    items.map { OrderItemDto(it.foodItemId, it.name, it.unitPrice.setScale(2).toPlainString(), it.quantity, it.lineTotal.setScale(2).toPlainString()) },
    pickupToken, createdAt.toString(), updatedAt.toString(),
)

@Serializable
data class StatusUpdateRequest(val status: OrderStatus)

@Serializable
data class OwnerDashboard(
    val todaySales: String,
    val placed: Int,
    val accepted: Int,
    val preparing: Int,
    val readyForPickup: Int,
    val completedToday: Int,
    val rejectedToday: Int,
)

// [from] is inclusive and [to] exclusive (UTC instants).
data class AdminOrderFilter(
    val status: OrderStatus? = null,
    val hotelId: Long? = null,
    val customerId: Long? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)
