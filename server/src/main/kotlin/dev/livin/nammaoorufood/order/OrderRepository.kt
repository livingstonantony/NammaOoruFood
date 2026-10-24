package dev.livin.nammaoorufood.order

import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
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
    // Locks only the order row so concurrent transitions on the same order are serialized.
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

class JdbcOrderRepository : OrderRepository {
    private val select = """
        SELECT o.id, o.customer_id, u.display_name AS customer_name, o.hotel_id, h.name AS hotel_name, o.status, o.total, o.note, o.created_at, o.updated_at,
               t.token_number
        FROM orders o
        JOIN hotels h ON h.id = o.hotel_id
        JOIN users u ON u.id = o.customer_id
        LEFT JOIN pickup_tokens t ON t.order_id = o.id
    """.trimIndent()

    override fun create(conn: Connection, customerId: Long, hotelId: Long, total: BigDecimal, note: String?, idempotencyKey: String?, items: List<NewOrderItem>): Long {
        val orderId = conn.prepareStatement(
            "INSERT INTO orders (customer_id, hotel_id, status, total, note, idempotency_key) VALUES (?, ?, 'PLACED', ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setLong(1, customerId); it.setLong(2, hotelId); it.setBigDecimal(3, total)
            it.setString(4, note); it.setString(5, idempotencyKey)
            it.executeUpdate()
            it.generatedKeys.use { k -> k.next(); k.getLong(1) }
        }
        conn.prepareStatement("INSERT INTO order_items (order_id, food_item_id, item_name, unit_price, quantity, line_total) VALUES (?, ?, ?, ?, ?, ?)").use {
            items.forEach { i ->
                it.setLong(1, orderId); it.setLong(2, i.foodItemId); it.setString(3, i.name)
                it.setBigDecimal(4, i.unitPrice); it.setInt(5, i.quantity); it.setBigDecimal(6, i.lineTotal)
                it.addBatch()
            }
            it.executeBatch()
        }
        return orderId
    }

    override fun findById(conn: Connection, id: Long): Order? =
        conn.prepareStatement("$select WHERE o.id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toOrder(conn) else null }
        }

    override fun findByIdempotencyKey(conn: Connection, customerId: Long, key: String): Order? =
        conn.prepareStatement("$select WHERE o.customer_id = ? AND o.idempotency_key = ?").use {
            it.setLong(1, customerId); it.setString(2, key)
            it.executeQuery().use { rs -> if (rs.next()) rs.toOrder(conn) else null }
        }

    override fun listForCustomer(conn: Connection, customerId: Long, status: OrderStatus?, limit: Int, offset: Int): List<Order> =
        conn.prepareStatement(
            "$select WHERE o.customer_id = ?${if (status != null) " AND o.status = ?" else ""} ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?",
        ).use {
            var i = 1
            it.setLong(i++, customerId)
            if (status != null) it.setString(i++, status.name)
            it.setInt(i++, limit); it.setInt(i, offset)
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toOrder(conn) else null }.toList() }
        }

    override fun countForCustomer(conn: Connection, customerId: Long, status: OrderStatus?): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM orders WHERE customer_id = ?${if (status != null) " AND status = ?" else ""}").use {
            it.setLong(1, customerId)
            if (status != null) it.setString(2, status.name)
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    override fun listActiveForCustomer(conn: Connection, customerId: Long): List<Order> =
        conn.prepareStatement("$select WHERE o.customer_id = ? AND o.status IN ('PLACED','ACCEPTED','PREPARING','READY_FOR_PICKUP') ORDER BY o.created_at DESC, o.id DESC").use {
            it.setLong(1, customerId)
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toOrder(conn) else null }.toList() }
        }

    private fun adminWhere(f: AdminOrderFilter): String = buildString {
        append("1 = 1")
        if (f.status != null) append(" AND o.status = ?")
        if (f.hotelId != null) append(" AND o.hotel_id = ?")
        if (f.customerId != null) append(" AND o.customer_id = ?")
        if (f.from != null) append(" AND o.created_at >= ?")
        if (f.to != null) append(" AND o.created_at < ?")
    }

    private fun java.sql.PreparedStatement.bindAdmin(f: AdminOrderFilter): Int {
        var i = 1
        f.status?.let { setString(i++, it.name) }
        f.hotelId?.let { setLong(i++, it) }
        f.customerId?.let { setLong(i++, it) }
        f.from?.let { setObject(i++, LocalDateTime.ofInstant(it, ZoneOffset.UTC)) }
        f.to?.let { setObject(i++, LocalDateTime.ofInstant(it, ZoneOffset.UTC)) }
        return i
    }

    override fun listForAdmin(conn: Connection, filter: AdminOrderFilter, limit: Int, offset: Int): List<Order> =
        conn.prepareStatement("$select WHERE ${adminWhere(filter)} ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?").use {
            var i = it.bindAdmin(filter)
            it.setInt(i++, limit); it.setInt(i, offset)
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toOrder(conn) else null }.toList() }
        }

    override fun countForAdmin(conn: Connection, filter: AdminOrderFilter): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM orders o WHERE ${adminWhere(filter)}").use {
            it.bindAdmin(filter)
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    override fun findByIdForUpdate(conn: Connection, id: Long): Order? =
        conn.prepareStatement("$select WHERE o.id = ? FOR UPDATE OF o").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toOrder(conn) else null }
        }

    override fun updateStatus(conn: Connection, id: Long, status: OrderStatus) {
        conn.prepareStatement("UPDATE orders SET status = ? WHERE id = ?").use {
            it.setString(1, status.name); it.setLong(2, id)
            it.executeUpdate()
        }
    }

    // Atomic per-hotel/day increment; the upsert row lock serializes concurrent token requests.
    override fun nextTokenNumber(conn: Connection, hotelId: Long, day: LocalDate): Int {
        conn.prepareStatement(
            "INSERT INTO hotel_token_counters (hotel_id, token_date, last_token) VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE last_token = last_token + 1",
        ).use {
            it.setLong(1, hotelId); it.setObject(2, day)
            it.executeUpdate()
        }
        return conn.prepareStatement("SELECT last_token FROM hotel_token_counters WHERE hotel_id = ? AND token_date = ?").use {
            it.setLong(1, hotelId); it.setObject(2, day)
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    override fun insertToken(conn: Connection, orderId: Long, hotelId: Long, day: LocalDate, number: Int) {
        conn.prepareStatement("INSERT INTO pickup_tokens (order_id, hotel_id, token_date, token_number) VALUES (?, ?, ?, ?)").use {
            it.setLong(1, orderId); it.setLong(2, hotelId); it.setObject(3, day); it.setInt(4, number)
            it.executeUpdate()
        }
    }

    private fun ownerFilter(hotelId: Long?, statuses: Collection<OrderStatus>) =
        "h.owner_id = ?" + (if (hotelId != null) " AND o.hotel_id = ?" else "") +
            (if (statuses.isNotEmpty()) " AND o.status IN (${statuses.joinToString(",") { "?" }})" else "")

    private fun java.sql.PreparedStatement.bindOwnerFilter(ownerId: Long, hotelId: Long?, statuses: Collection<OrderStatus>): Int {
        var i = 1
        setLong(i++, ownerId)
        if (hotelId != null) setLong(i++, hotelId)
        statuses.forEach { setString(i++, it.name) }
        return i
    }

    override fun listForOwner(conn: Connection, ownerId: Long, hotelId: Long?, statuses: Collection<OrderStatus>, limit: Int, offset: Int): List<Order> =
        conn.prepareStatement("$select WHERE ${ownerFilter(hotelId, statuses)} ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?").use {
            var i = it.bindOwnerFilter(ownerId, hotelId, statuses)
            it.setInt(i++, limit); it.setInt(i, offset)
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toOrder(conn) else null }.toList() }
        }

    override fun countForOwner(conn: Connection, ownerId: Long, hotelId: Long?, statuses: Collection<OrderStatus>): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM orders o JOIN hotels h ON h.id = o.hotel_id WHERE ${ownerFilter(hotelId, statuses)}").use {
            it.bindOwnerFilter(ownerId, hotelId, statuses)
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    // Live statuses are counted regardless of age; terminal statuses only since [since].
    override fun statusCountsForOwner(conn: Connection, ownerId: Long, since: Instant): Map<OrderStatus, Int> =
        conn.prepareStatement(
            """SELECT o.status, COUNT(*) FROM orders o JOIN hotels h ON h.id = o.hotel_id
               WHERE h.owner_id = ? AND (o.status IN ('PLACED','ACCEPTED','PREPARING','READY_FOR_PICKUP') OR o.updated_at >= ?)
               GROUP BY o.status""",
        ).use {
            it.setLong(1, ownerId); it.setObject(2, LocalDateTime.ofInstant(since, ZoneOffset.UTC))
            it.executeQuery().use { rs ->
                val out = mutableMapOf<OrderStatus, Int>()
                while (rs.next()) out[OrderStatus.valueOf(rs.getString(1))] = rs.getInt(2)
                out
            }
        }

    override fun salesForOwner(conn: Connection, ownerId: Long, since: Instant): BigDecimal =
        conn.prepareStatement(
            "SELECT COALESCE(SUM(o.total), 0) FROM orders o JOIN hotels h ON h.id = o.hotel_id WHERE h.owner_id = ? AND o.status = 'COMPLETED' AND o.updated_at >= ?",
        ).use {
            it.setLong(1, ownerId); it.setObject(2, LocalDateTime.ofInstant(since, ZoneOffset.UTC))
            it.executeQuery().use { rs -> rs.next(); rs.getBigDecimal(1) }
        }

    private fun loadItems(conn: Connection, orderId: Long): List<OrderItem> =
        conn.prepareStatement("SELECT food_item_id, item_name, unit_price, quantity, line_total FROM order_items WHERE order_id = ? ORDER BY id").use {
            it.setLong(1, orderId)
            it.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) OrderItem(rs.getObject("food_item_id") as Long?, rs.getString("item_name"), rs.getBigDecimal("unit_price"), rs.getInt("quantity"), rs.getBigDecimal("line_total")) else null
                }.toList()
            }
        }

    private fun ResultSet.toOrder(conn: Connection): Order {
        val id = getLong("id")
        return Order(
            id = id,
            customerId = getLong("customer_id"),
            customerName = getString("customer_name"),
            hotelId = getLong("hotel_id"),
            hotelName = getString("hotel_name"),
            status = OrderStatus.valueOf(getString("status")),
            total = getBigDecimal("total"),
            note = getString("note"),
            items = loadItems(conn, id),
            pickupToken = getObject("token_number") as Int?,
            createdAt = getObject("created_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
            updatedAt = getObject("updated_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
        )
    }
}
