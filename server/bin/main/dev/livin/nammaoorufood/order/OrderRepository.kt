package dev.livin.nammaoorufood.order

import dev.livin.nammaoorufood.database.HotelTokenCountersTable
import dev.livin.nammaoorufood.database.HotelsTable
import dev.livin.nammaoorufood.database.OrderItemsTable
import dev.livin.nammaoorufood.database.OrdersTable
import dev.livin.nammaoorufood.database.PickupTokensTable
import dev.livin.nammaoorufood.database.UsersTable
import dev.livin.nammaoorufood.user.Role
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.leftJoin
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

interface OrderRepository {
    fun create(conn: Connection, customerId: Long, hotelId: Long, total: BigDecimal, note: String?, idempotencyKey: String?, items: List<NewOrderItem>): Long
    fun findById(conn: Connection, id: Long): Order?
    fun findByIdempotencyKey(conn: Connection, customerId: Long, key: String): Order?
    fun listForCustomer(conn: Connection, customerId: Long, status: OrderStatus?, limit: Int, offset: Int): List<Order>
    fun countForCustomer(conn: Connection, customerId: Long, status: OrderStatus?): Int
    fun listActiveForCustomer(conn: Connection, customerId: Long): List<Order>
    fun listForAdmin(conn: Connection, filter: AdminOrderFilter, limit: Int, offset: Int): List<Order>
    fun countForAdmin(conn: Connection, filter: AdminOrderFilter): Int
    fun findByIdForUpdate(conn: Connection, id: Long): Order?
    fun updateStatus(conn: Connection, id: Long, status: OrderStatus)
    fun nextTokenNumber(conn: Connection, hotelId: Long, day: LocalDate): Int
    fun insertToken(conn: Connection, orderId: Long, hotelId: Long, day: LocalDate, number: Int)
    fun listForOwner(conn: Connection, ownerId: Long, hotelId: Long?, statuses: Collection<OrderStatus>, limit: Int, offset: Int): List<Order>
    fun countForOwner(conn: Connection, ownerId: Long, hotelId: Long?, statuses: Collection<OrderStatus>): Int
    fun statusCountsForOwner(conn: Connection, ownerId: Long, since: Instant): Map<OrderStatus, Int>
    fun salesForOwner(conn: Connection, ownerId: Long, since: Instant): BigDecimal
}

private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

class ExposedOrderRepository : OrderRepository {
    private val orderDetails = OrdersTable
        .innerJoin(HotelsTable, { OrdersTable.hotelId }, { HotelsTable.id })
        .innerJoin(UsersTable, { OrdersTable.customerId }, { UsersTable.id })
        .leftJoin(PickupTokensTable, { OrdersTable.id }, { PickupTokensTable.orderId })

    override fun create(
        conn: Connection,
        customerId: Long,
        hotelId: Long,
        total: BigDecimal,
        note: String?,
        idempotencyKey: String?,
        items: List<NewOrderItem>,
    ): Long {
        val orderId = try {
            OrdersTable.insert {
                it[OrdersTable.customerId] = customerId
                it[OrdersTable.hotelId] = hotelId
                it[OrdersTable.status] = OrderStatus.PLACED
                it[OrdersTable.total] = total
                it[OrdersTable.note] = note
                it[OrdersTable.idempotencyKey] = idempotencyKey
            }[OrdersTable.id]
        } catch (failure: ExposedSQLException) {
            val integrityFailure = generateSequence(failure.cause) { it.cause }
                .filterIsInstance<SQLIntegrityConstraintViolationException>()
                .firstOrNull()
            if (integrityFailure != null) throw integrityFailure
            throw failure
        }
        items.forEach { item ->
            OrderItemsTable.insert {
                it[OrderItemsTable.orderId] = orderId
                it[OrderItemsTable.foodItemId] = item.foodItemId
                it[OrderItemsTable.itemName] = item.name
                it[OrderItemsTable.unitPrice] = item.unitPrice
                it[OrderItemsTable.quantity] = item.quantity
                it[OrderItemsTable.lineTotal] = item.lineTotal
            }
        }
        return orderId
    }

    override fun findById(conn: Connection, id: Long): Order? =
        orderDetails.selectAll().where { OrdersTable.id eq id }.limit(1).singleOrNull()?.toOrder()

    override fun findByIdempotencyKey(conn: Connection, customerId: Long, key: String): Order? =
        orderDetails.selectAll().where {
            (OrdersTable.customerId eq customerId) and (OrdersTable.idempotencyKey eq key)
        }.limit(1).singleOrNull()?.toOrder()

    override fun listForCustomer(conn: Connection, customerId: Long, status: OrderStatus?, limit: Int, offset: Int): List<Order> =
        orderDetails.selectAll().where {
            (OrdersTable.customerId eq customerId) and (status?.let { OrdersTable.status eq it } ?: Op.TRUE)
        }
            .orderBy(OrdersTable.createdAt to SortOrder.DESC, OrdersTable.id to SortOrder.DESC)
            .limit(limit).offset(offset.toLong())
            .map { it.toOrder() }

    override fun countForCustomer(conn: Connection, customerId: Long, status: OrderStatus?): Int =
        OrdersTable.selectAll().where {
            (OrdersTable.customerId eq customerId) and (status?.let { OrdersTable.status eq it } ?: Op.TRUE)
        }.count().toInt()

    override fun listActiveForCustomer(conn: Connection, customerId: Long): List<Order> =
        orderDetails.selectAll().where {
            (OrdersTable.customerId eq customerId) and
                (OrdersTable.status inList listOf(OrderStatus.PLACED, OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP))
        }
            .orderBy(OrdersTable.createdAt to SortOrder.DESC, OrdersTable.id to SortOrder.DESC)
            .map { it.toOrder() }

    private fun adminConditions(filter: AdminOrderFilter): List<Op<Boolean>> = buildList {
        filter.status?.let { add(OrdersTable.status eq it) }
        filter.hotelId?.let { add(OrdersTable.hotelId eq it) }
        filter.customerId?.let { add(OrdersTable.customerId eq it) }
        filter.from?.let { add(OrdersTable.createdAt greaterEq it.utc()) }
        filter.to?.let { add(OrdersTable.createdAt less it.utc()) }
    }

    override fun listForAdmin(conn: Connection, filter: AdminOrderFilter, limit: Int, offset: Int): List<Order> {
        val conditions = adminConditions(filter)
        return orderDetails.selectAll().apply {
            if (conditions.isNotEmpty()) where { conditions.reduce { left, right -> left and right } }
        }
            .orderBy(OrdersTable.createdAt to SortOrder.DESC, OrdersTable.id to SortOrder.DESC)
            .limit(limit).offset(offset.toLong())
            .map { it.toOrder() }
    }

    override fun countForAdmin(conn: Connection, filter: AdminOrderFilter): Int {
        val conditions = adminConditions(filter)
        return OrdersTable.selectAll().apply {
            if (conditions.isNotEmpty()) where { conditions.reduce { left, right -> left and right } }
        }.count().toInt()
    }

    override fun findByIdForUpdate(conn: Connection, id: Long): Order? {
        val locked = OrdersTable.selectAll().where { OrdersTable.id eq id }.forUpdate().limit(1).singleOrNull()
            ?: return null
        return findById(conn, locked[OrdersTable.id])
    }

    override fun updateStatus(conn: Connection, id: Long, status: OrderStatus) {
        OrdersTable.update({ OrdersTable.id eq id }) { it[OrdersTable.status] = status }
    }

    override fun nextTokenNumber(conn: Connection, hotelId: Long, day: LocalDate): Int {
        HotelTokenCountersTable.upsert(
            onUpdate = {
                it[HotelTokenCountersTable.lastToken] = HotelTokenCountersTable.lastToken + 1
            },
        ) {
            it[HotelTokenCountersTable.hotelId] = hotelId
            it[tokenDate] = day
            it[lastToken] = 1
        }
        return HotelTokenCountersTable.selectAll().where {
            (HotelTokenCountersTable.hotelId eq hotelId) and (HotelTokenCountersTable.tokenDate eq day)
        }.single()[HotelTokenCountersTable.lastToken]
    }

    override fun insertToken(conn: Connection, orderId: Long, hotelId: Long, day: LocalDate, number: Int) {
        PickupTokensTable.insert {
            it[PickupTokensTable.orderId] = orderId
            it[PickupTokensTable.hotelId] = hotelId
            it[PickupTokensTable.tokenDate] = day
            it[PickupTokensTable.tokenNumber] = number
        }
    }

    private fun ownerConditions(ownerId: Long, hotelId: Long?, statuses: Collection<OrderStatus>): List<Op<Boolean>> = buildList {
        add(HotelsTable.ownerId eq ownerId)
        hotelId?.let { add(OrdersTable.hotelId eq it) }
        if (statuses.isNotEmpty()) add(OrdersTable.status inList statuses)
    }

    override fun listForOwner(
        conn: Connection,
        ownerId: Long,
        hotelId: Long?,
        statuses: Collection<OrderStatus>,
        limit: Int,
        offset: Int,
    ): List<Order> =
        orderDetails.selectAll().where { ownerConditions(ownerId, hotelId, statuses).reduce { left, right -> left and right } }
            .orderBy(OrdersTable.createdAt to SortOrder.DESC, OrdersTable.id to SortOrder.DESC)
            .limit(limit).offset(offset.toLong())
            .map { it.toOrder() }

    override fun countForOwner(conn: Connection, ownerId: Long, hotelId: Long?, statuses: Collection<OrderStatus>): Int =
        OrdersTable.innerJoin(HotelsTable, { OrdersTable.hotelId }, { HotelsTable.id })
            .selectAll()
            .where { ownerConditions(ownerId, hotelId, statuses).reduce { left, right -> left and right } }
            .count().toInt()

    override fun statusCountsForOwner(conn: Connection, ownerId: Long, since: Instant): Map<OrderStatus, Int> =
        OrdersTable.innerJoin(HotelsTable, { OrdersTable.hotelId }, { HotelsTable.id })
            .select(OrdersTable.status, OrdersTable.id.count())
            .where {
                (HotelsTable.ownerId eq ownerId) and
                    ((OrdersTable.status inList listOf(OrderStatus.PLACED, OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP)) or
                        (OrdersTable.updatedAt greaterEq since.utc()))
            }
            .groupBy(OrdersTable.status)
            .associate { it[OrdersTable.status] to it[OrdersTable.id.count()].toInt() }

    override fun salesForOwner(conn: Connection, ownerId: Long, since: Instant): BigDecimal =
        OrdersTable.innerJoin(HotelsTable, { OrdersTable.hotelId }, { HotelsTable.id })
            .select(OrdersTable.total.sum())
            .where {
                (HotelsTable.ownerId eq ownerId) and
                    (OrdersTable.status eq OrderStatus.COMPLETED) and
                    (OrdersTable.updatedAt greaterEq since.utc())
            }
            .single()[OrdersTable.total.sum()] ?: BigDecimal.ZERO

    private fun ResultRow.toOrder(): Order {
        val orderId = this[OrdersTable.id]
        val items = OrderItemsTable.selectAll().where { OrderItemsTable.orderId eq orderId }
            .orderBy(OrderItemsTable.id to SortOrder.ASC)
            .map {
                OrderItem(
                    it[OrderItemsTable.foodItemId],
                    it[OrderItemsTable.itemName],
                    it[OrderItemsTable.unitPrice],
                    it[OrderItemsTable.quantity],
                    it[OrderItemsTable.lineTotal],
                )
            }
        return Order(
            id = orderId,
            customerId = this[OrdersTable.customerId],
            customerName = this[UsersTable.displayName],
            hotelId = this[OrdersTable.hotelId],
            hotelName = this[HotelsTable.name],
            status = this[OrdersTable.status],
            total = this[OrdersTable.total],
            note = this[OrdersTable.note],
            items = items,
            pickupToken = this.getOrNull(PickupTokensTable.tokenNumber),
            createdAt = this[OrdersTable.createdAt].toInstant(ZoneOffset.UTC),
            updatedAt = this[OrdersTable.updatedAt].toInstant(ZoneOffset.UTC),
        )
    }
}
