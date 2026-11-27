package dev.livin.nammaoorufood.telegram

import dev.livin.nammaoorufood.order.Order
import dev.livin.nammaoorufood.order.OrderStatus
import java.sql.Connection

object TelegramOutbox {
    fun orderChanged(conn: Connection, order: Order) {
        val text = when (order.status) {
            OrderStatus.PLACED -> "Order #${order.id} placed at ${order.hotelName}. Payment is at pickup. To cancel before the hotel responds, use /cancel ${order.id}."
            OrderStatus.ACCEPTED -> "Order #${order.id} accepted by ${order.hotelName}. Pickup token: ${order.pickupToken}."
            OrderStatus.REJECTED -> "Order #${order.id} was rejected by ${order.hotelName}."
            OrderStatus.PREPARING -> "Order #${order.id} is being prepared by ${order.hotelName}."
            OrderStatus.READY_FOR_PICKUP -> "Order #${order.id} is ready for pickup at ${order.hotelName}. Pickup token: ${order.pickupToken}."
            OrderStatus.COMPLETED -> "Order #${order.id} at ${order.hotelName} is completed."
            OrderStatus.CANCELLED -> "Order #${order.id} at ${order.hotelName} was cancelled."
        }
        conn.prepareStatement(
            """INSERT INTO telegram_outbox (user_id, order_id, order_status, message)
               SELECT ?, ?, ?, ? FROM telegram_identities WHERE user_id = ?""",
        ).use {
            it.setLong(1, order.customerId)
            it.setLong(2, order.id)
            it.setString(3, order.status.name)
            it.setString(4, text)
            it.setLong(5, order.customerId)
            it.executeUpdate()
        }
    }

    fun reply(conn: Connection, userId: Long, updateId: Long, text: String, openMiniApp: Boolean = false) {
        conn.prepareStatement("INSERT INTO telegram_outbox (user_id, update_id, message, open_mini_app) VALUES (?, ?, ?, ?)").use {
            it.setLong(1, userId)
            it.setLong(2, updateId)
            it.setString(3, text)
            it.setBoolean(4, openMiniApp)
            it.executeUpdate()
        }
    }
}
