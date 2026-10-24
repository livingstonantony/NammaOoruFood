package dev.livin.nammaoorufood.admin

import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

data class HotelSalesRaw(val hotelId: Long, val hotelName: String, val placed: Int, val completed: Int, val rejected: Int, val sales: BigDecimal)

interface AdminRepository {
    fun searchCustomers(conn: Connection, query: String?, active: Boolean?, limit: Int, offset: Int): List<CustomerDto>
    fun countCustomers(conn: Connection, query: String?, active: Boolean?): Int
    fun findCustomer(conn: Connection, id: Long): CustomerDto?
    fun setCustomerActive(conn: Connection, id: Long, active: Boolean)
    // Placed counts use created_at; completed/rejected/sales use the time the order reached that state (updated_at).
    fun hotelSales(conn: Connection, from: Instant, to: Instant): List<HotelSalesRaw>
    fun customerCounts(conn: Connection): Pair<Int, Int>
    fun hotelCounts(conn: Connection): Pair<Int, Int>
}

private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

class JdbcAdminRepository : AdminRepository {
    private val customerSelect = """
        SELECT u.id, u.username, u.display_name, u.phone, u.active, u.created_at,
               (SELECT COUNT(*) FROM orders o WHERE o.customer_id = u.id) AS order_count
        FROM users u
    """.trimIndent()

    private fun where(query: String?, active: Boolean?) = buildString {
        append("u.role = 'CUSTOMER'")
        if (!query.isNullOrBlank()) append(" AND (u.username LIKE ? OR u.display_name LIKE ? OR u.phone LIKE ?)")
        if (active != null) append(" AND u.active = ?")
    }

    private fun java.sql.PreparedStatement.bind(query: String?, active: Boolean?): Int {
        var i = 1
        if (!query.isNullOrBlank()) {
            val like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            repeat(3) { setString(i++, like) }
        }
        if (active != null) setBoolean(i++, active)
        return i
    }

    override fun searchCustomers(conn: Connection, query: String?, active: Boolean?, limit: Int, offset: Int): List<CustomerDto> =
        conn.prepareStatement("$customerSelect WHERE ${where(query, active)} ORDER BY u.created_at DESC, u.id DESC LIMIT ? OFFSET ?").use {
            var i = it.bind(query, active)
            it.setInt(i++, limit); it.setInt(i, offset)
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toCustomer() else null }.toList() }
        }

    override fun countCustomers(conn: Connection, query: String?, active: Boolean?): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM users u WHERE ${where(query, active)}").use {
            it.bind(query, active)
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    override fun findCustomer(conn: Connection, id: Long): CustomerDto? =
        conn.prepareStatement("$customerSelect WHERE u.role = 'CUSTOMER' AND u.id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toCustomer() else null }
        }

    override fun setCustomerActive(conn: Connection, id: Long, active: Boolean) {
        conn.prepareStatement("UPDATE users SET active = ? WHERE id = ? AND role = 'CUSTOMER'").use {
            it.setBoolean(1, active); it.setLong(2, id)
            it.executeUpdate()
        }
    }

    override fun hotelSales(conn: Connection, from: Instant, to: Instant): List<HotelSalesRaw> =
        conn.prepareStatement(
            """SELECT h.id, h.name,
                 (SELECT COUNT(*) FROM orders o WHERE o.hotel_id = h.id AND o.created_at >= ? AND o.created_at < ?) AS placed,
                 (SELECT COUNT(*) FROM orders o WHERE o.hotel_id = h.id AND o.status = 'COMPLETED' AND o.updated_at >= ? AND o.updated_at < ?) AS completed,
                 (SELECT COUNT(*) FROM orders o WHERE o.hotel_id = h.id AND o.status = 'REJECTED' AND o.updated_at >= ? AND o.updated_at < ?) AS rejected,
                 (SELECT COALESCE(SUM(o.total), 0) FROM orders o WHERE o.hotel_id = h.id AND o.status = 'COMPLETED' AND o.updated_at >= ? AND o.updated_at < ?) AS sales
               FROM hotels h ORDER BY sales DESC, h.name""",
        ).use {
            for (i in 0 until 4) { it.setObject(i * 2 + 1, from.utc()); it.setObject(i * 2 + 2, to.utc()) }
            it.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) HotelSalesRaw(rs.getLong("id"), rs.getString("name"), rs.getInt("placed"), rs.getInt("completed"), rs.getInt("rejected"), rs.getBigDecimal("sales")) else null
                }.toList()
            }
        }

    override fun customerCounts(conn: Connection): Pair<Int, Int> =
        conn.prepareStatement("SELECT COUNT(*), COALESCE(SUM(active), 0) FROM users WHERE role = 'CUSTOMER'").use {
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) to rs.getInt(2) }
        }

    override fun hotelCounts(conn: Connection): Pair<Int, Int> =
        conn.prepareStatement("SELECT COALESCE(SUM(approval_status = 'APPROVED'), 0), COALESCE(SUM(approval_status = 'PENDING'), 0) FROM hotels").use {
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) to rs.getInt(2) }
        }

    private fun java.sql.ResultSet.toCustomer() = CustomerDto(
        getLong("id"), getString("username"), getString("display_name"), getString("phone"), getBoolean("active"),
        getObject("created_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC).toString(), getInt("order_count"),
    )
}
