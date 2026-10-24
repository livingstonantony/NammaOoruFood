package dev.livin.nammaoorufood.order

import dev.livin.nammaoorufood.auth.UserPrincipal
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.hotel.ApprovalStatus
import dev.livin.nammaoorufood.hotel.BUSINESS_ZONE
import dev.livin.nammaoorufood.hotel.HotelRepository
import dev.livin.nammaoorufood.hotel.OperatingHours
import dev.livin.nammaoorufood.notification.NotificationRepository
import dev.livin.nammaoorufood.notification.NotificationType
import dev.livin.nammaoorufood.plugins.ConflictException
import dev.livin.nammaoorufood.plugins.ForbiddenException
import dev.livin.nammaoorufood.plugins.NotFoundException
import dev.livin.nammaoorufood.plugins.ValidationException
import dev.livin.nammaoorufood.user.Role
import java.math.BigDecimal
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.LocalTime

class OrderService(
    private val db: Database,
    private val orders: OrderRepository,
    private val hotels: HotelRepository,
    private val notifications: NotificationRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun place(p: UserPrincipal, req: PlaceOrderRequest, idempotencyKey: String?): Pair<OrderDto, Boolean> {
        if (p.role != Role.CUSTOMER) throw ForbiddenException()
        if (idempotencyKey != null && !KEY.matches(idempotencyKey)) throw ValidationException("Invalid Idempotency-Key")
        val note = req.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > 255) throw ValidationException("Note must be at most 255 characters")
        if (req.items.isEmpty()) throw ValidationException("Order must contain at least one item")
        if (req.items.any { it.quantity !in 1..MAX_QUANTITY }) throw ValidationException("Quantity must be between 1 and $MAX_QUANTITY")
        // Duplicate lines for the same item are merged.
        val quantities = req.items.groupBy { it.foodItemId }.mapValues { (_, lines) -> lines.sumOf { it.quantity } }
        if (quantities.size > MAX_LINES || quantities.values.any { it > MAX_QUANTITY }) throw ValidationException("Order is too large")

        if (idempotencyKey != null) {
            db.transaction { orders.findByIdempotencyKey(it, p.userId, idempotencyKey) }?.let { return it.toDto() to false }
        }

        return try {
            db.transaction { conn ->
                val hotel = hotels.findById(conn, req.hotelId)?.takeIf { it.approvalStatus == ApprovalStatus.APPROVED }
                    ?: throw NotFoundException("Hotel not found")
                val hours = hotels.hoursFor(conn, listOf(hotel.id))[hotel.id].orEmpty()
                val open = hotel.acceptingOrders && OperatingHours.isOpenAt(hours, ZonedDateTime.now(clock.withZone(BUSINESS_ZONE)))
                if (!open) throw ConflictException("Hotel is not accepting orders right now")

                val lines = quantities.map { (itemId, qty) ->
                    val item = hotels.findFoodItem(conn, hotel.id, itemId)
                        ?: throw ValidationException("Item $itemId does not belong to this hotel")
                    if (!item.available) throw ConflictException("'${item.name}' is currently unavailable")
                    NewOrderItem(item.id, item.name, item.price, qty)
                }
                val total = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.lineTotal }
                val id = orders.create(conn, p.userId, hotel.id, total, note, idempotencyKey, lines)
                orders.findById(conn, id)!!.toDto() to true
            }
        } catch (e: SQLIntegrityConstraintViolationException) {
            // A concurrent request with the same key won the race; return its order.
            val existing = idempotencyKey?.let { key -> db.transaction { orders.findByIdempotencyKey(it, p.userId, key) } } ?: throw e
            existing.toDto() to false
        }
    }

    fun list(p: UserPrincipal, status: OrderStatus?, page: Int, pageSize: Int): OrderPage {
        if (p.role != Role.CUSTOMER) throw ForbiddenException()
        val size = pageSize.coerceIn(1, 50)
        val pageNo = page.coerceAtLeast(1)
        return db.transaction { conn ->
            OrderPage(
                items = orders.listForCustomer(conn, p.userId, status, size, (pageNo - 1) * size).map { it.toDto() },
                page = pageNo,
                pageSize = size,
                total = orders.countForCustomer(conn, p.userId, status),
            )
        }
    }

    // Anyone not entitled to see the order gets 404 so order ids cannot be probed.
    fun get(p: UserPrincipal, id: Long): OrderDto = db.transaction { conn ->
        val order = orders.findById(conn, id) ?: throw NotFoundException("Order not found")
        val allowed = when (p.role) {
            Role.CUSTOMER -> order.customerId == p.userId
            Role.HOTEL_OWNER -> hotels.findById(conn, order.hotelId)?.ownerId == p.userId
            Role.ADMIN -> true
        }
        if (!allowed) throw NotFoundException("Order not found")
        order.toDto()
    }

    fun accept(p: UserPrincipal, orderId: Long): OrderDto = transition(p, orderId, OrderStatus.ACCEPTED)

    fun reject(p: UserPrincipal, orderId: Long): OrderDto = transition(p, orderId, OrderStatus.REJECTED)

    // Customers may cancel only their own order, and only before the hotel has responded.
    fun cancel(p: UserPrincipal, orderId: Long): OrderDto {
        if (p.role != Role.CUSTOMER) throw ForbiddenException()
        return db.transaction { conn ->
            val order = orders.findByIdForUpdate(conn, orderId)?.takeIf { it.customerId == p.userId }
                ?: throw NotFoundException("Order not found")
            if (!OrderStateMachine.canTransition(order.status, OrderStatus.CANCELLED)) {
                throw ConflictException("Order can no longer be cancelled")
            }
            orders.updateStatus(conn, order.id, OrderStatus.CANCELLED)
            orders.findById(conn, order.id)!!.toDto()
        }
    }

    fun updateStatus(p: UserPrincipal, orderId: Long, status: OrderStatus): OrderDto {
        if (status == OrderStatus.ACCEPTED || status == OrderStatus.REJECTED || status == OrderStatus.PLACED || status == OrderStatus.CANCELLED) {
            throw ValidationException("Use the accept or reject endpoints for this transition")
        }
        return transition(p, orderId, status)
    }

    // The order row is locked for the whole transition, so a duplicate accept sees ACCEPTED and fails.
    private fun transition(p: UserPrincipal, orderId: Long, target: OrderStatus): OrderDto {
        if (p.role != Role.HOTEL_OWNER) throw ForbiddenException()
        return db.transaction { conn ->
            val order = orders.findByIdForUpdate(conn, orderId) ?: throw NotFoundException("Order not found")
            if (hotels.findById(conn, order.hotelId)?.ownerId != p.userId) throw NotFoundException("Order not found")
            if (!OrderStateMachine.canTransition(order.status, target)) {
                throw ConflictException("Cannot change order from ${order.status} to $target")
            }
            if (target == OrderStatus.ACCEPTED) {
                val day = ZonedDateTime.now(clock.withZone(BUSINESS_ZONE)).toLocalDate()
                orders.insertToken(conn, order.id, order.hotelId, day, orders.nextTokenNumber(conn, order.hotelId, day))
            }
            orders.updateStatus(conn, order.id, target)
            val updated = orders.findById(conn, order.id)!!
            notify(conn, updated)
            updated.toDto()
        }
    }

    // Written in the same transaction as the status change, so a notification exists iff the status was persisted.
    private fun notify(conn: java.sql.Connection, o: Order) {
        val (type, title, message) = when (o.status) {
            OrderStatus.ACCEPTED -> Triple(NotificationType.ORDER_ACCEPTED, "Order accepted", "${o.hotelName} accepted your order. Your token is ${o.pickupToken}.")
            OrderStatus.REJECTED -> Triple(NotificationType.ORDER_REJECTED, "Order rejected", "${o.hotelName} could not accept your order.")
            OrderStatus.PREPARING -> Triple(NotificationType.ORDER_PREPARING, "Preparing your food", "${o.hotelName} is preparing your order.")
            OrderStatus.READY_FOR_PICKUP -> Triple(NotificationType.ORDER_READY, "Order ready for pickup", "Your order is ready at ${o.hotelName}. Show token ${o.pickupToken}.")
            else -> return
        }
        notifications.insert(conn, o.customerId, o.id, type, title, message)
    }

    fun active(p: UserPrincipal): List<OrderDto> {
        if (p.role != Role.CUSTOMER) throw ForbiddenException()
        return db.transaction { conn ->
            orders.listActiveForCustomer(conn, p.userId).map { it.toDto() }
        }
    }

    fun ownerOrders(p: UserPrincipal, hotelId: Long?, statuses: Set<OrderStatus>, page: Int, pageSize: Int): OrderPage {
        if (p.role != Role.HOTEL_OWNER) throw ForbiddenException()
        val size = pageSize.coerceIn(1, 100)
        val pageNo = page.coerceAtLeast(1)
        return db.transaction { conn ->
            OrderPage(
                items = orders.listForOwner(conn, p.userId, hotelId, statuses, size, (pageNo - 1) * size).map { it.toDto() },
                page = pageNo,
                pageSize = size,
                total = orders.countForOwner(conn, p.userId, hotelId, statuses),
            )
        }
    }

    fun dashboard(p: UserPrincipal): OwnerDashboard {
        if (p.role != Role.HOTEL_OWNER) throw ForbiddenException()
        val startOfDay = ZonedDateTime.now(clock.withZone(BUSINESS_ZONE)).with(LocalTime.MIN).toInstant()
        return db.transaction { conn ->
            val counts = orders.statusCountsForOwner(conn, p.userId, startOfDay)
            OwnerDashboard(
                todaySales = orders.salesForOwner(conn, p.userId, startOfDay).setScale(2).toPlainString(),
                placed = counts[OrderStatus.PLACED] ?: 0,
                accepted = counts[OrderStatus.ACCEPTED] ?: 0,
                preparing = counts[OrderStatus.PREPARING] ?: 0,
                readyForPickup = counts[OrderStatus.READY_FOR_PICKUP] ?: 0,
                completedToday = counts[OrderStatus.COMPLETED] ?: 0,
                rejectedToday = counts[OrderStatus.REJECTED] ?: 0,
            )
        }
    }

    private companion object {
        const val MAX_QUANTITY = 20
        const val MAX_LINES = 30
        val KEY = Regex("^[A-Za-z0-9_-]{8,64}$")
    }
}
