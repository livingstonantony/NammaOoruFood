package dev.livin.nammaoorufood.hotel

import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.time.LocalTime

interface HotelRepository {
    fun create(conn: Connection, ownerId: Long, name: String, description: String?, address: String, phone: String?): Hotel
    fun update(conn: Connection, id: Long, name: String, description: String?, address: String, phone: String?)
    fun findById(conn: Connection, id: Long): Hotel?
    fun search(conn: Connection, query: String?, approvedOnly: Boolean, ownerId: Long?): List<Hotel>
    fun setApproval(conn: Connection, id: Long, status: ApprovalStatus)
    fun setAcceptingOrders(conn: Connection, id: Long, accepting: Boolean)
    fun hoursFor(conn: Connection, hotelIds: Collection<Long>): Map<Long, List<OperatingHour>>
    fun replaceHours(conn: Connection, hotelId: Long, hours: List<OperatingHour>)

    fun categories(conn: Connection, hotelId: Long): List<MenuCategory>
    fun createCategory(conn: Connection, hotelId: Long, name: String, sortOrder: Int): MenuCategory
    fun categoryBelongsToHotel(conn: Connection, categoryId: Long, hotelId: Long): Boolean
    fun foodItems(conn: Connection, hotelId: Long): List<FoodItem>
    fun findFoodItem(conn: Connection, hotelId: Long, itemId: Long): FoodItem?
    fun createFoodItem(conn: Connection, hotelId: Long, categoryId: Long?, name: String, description: String?, price: BigDecimal, available: Boolean): FoodItem
    fun updateFoodItem(conn: Connection, hotelId: Long, itemId: Long, categoryId: Long?, name: String, description: String?, price: BigDecimal, available: Boolean): Boolean
    fun setItemAvailability(conn: Connection, hotelId: Long, itemId: Long, available: Boolean): Boolean
    fun deleteFoodItem(conn: Connection, hotelId: Long, itemId: Long): Boolean
}

class JdbcHotelRepository : HotelRepository {
    private val hotelCols = "id, owner_id, name, description, address, phone, approval_status, accepting_orders"

    override fun create(conn: Connection, ownerId: Long, name: String, description: String?, address: String, phone: String?): Hotel {
        val id = conn.prepareStatement(
            "INSERT INTO hotels (owner_id, name, description, address, phone) VALUES (?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setLong(1, ownerId); it.setString(2, name); it.setString(3, description); it.setString(4, address); it.setString(5, phone)
            it.executeUpdate()
            it.generatedKeys.use { k -> k.next(); k.getLong(1) }
        }
        return Hotel(id, ownerId, name, description, address, phone, ApprovalStatus.PENDING, false)
    }

    override fun update(conn: Connection, id: Long, name: String, description: String?, address: String, phone: String?) {
        conn.prepareStatement("UPDATE hotels SET name = ?, description = ?, address = ?, phone = ? WHERE id = ?").use {
            it.setString(1, name); it.setString(2, description); it.setString(3, address); it.setString(4, phone); it.setLong(5, id)
            it.executeUpdate()
        }
    }

    override fun findById(conn: Connection, id: Long): Hotel? =
        conn.prepareStatement("SELECT $hotelCols FROM hotels WHERE id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toHotel() else null }
        }

    override fun search(conn: Connection, query: String?, approvedOnly: Boolean, ownerId: Long?): List<Hotel> {
        val where = mutableListOf("1 = 1")
        if (approvedOnly) where += "approval_status = 'APPROVED'"
        if (ownerId != null) where += "owner_id = ?"
        if (!query.isNullOrBlank()) where += "name LIKE ?"
        return conn.prepareStatement("SELECT $hotelCols FROM hotels WHERE ${where.joinToString(" AND ")} ORDER BY name").use {
            var i = 1
            if (ownerId != null) it.setLong(i++, ownerId)
            if (!query.isNullOrBlank()) {
                val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                it.setString(i, "%$escaped%")
            }
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toHotel() else null }.toList() }
        }
    }

    override fun setApproval(conn: Connection, id: Long, status: ApprovalStatus) {
        conn.prepareStatement("UPDATE hotels SET approval_status = ?, accepting_orders = IF(? = 'APPROVED', accepting_orders, FALSE) WHERE id = ?").use {
            it.setString(1, status.name); it.setString(2, status.name); it.setLong(3, id)
            it.executeUpdate()
        }
    }

    override fun setAcceptingOrders(conn: Connection, id: Long, accepting: Boolean) {
        conn.prepareStatement("UPDATE hotels SET accepting_orders = ? WHERE id = ?").use {
            it.setBoolean(1, accepting); it.setLong(2, id)
            it.executeUpdate()
        }
    }

    override fun hoursFor(conn: Connection, hotelIds: Collection<Long>): Map<Long, List<OperatingHour>> {
        if (hotelIds.isEmpty()) return emptyMap()
        val marks = hotelIds.joinToString(",") { "?" }
        return conn.prepareStatement("SELECT hotel_id, day_of_week, opens_at, closes_at FROM hotel_operating_hours WHERE hotel_id IN ($marks) ORDER BY day_of_week").use {
            hotelIds.forEachIndexed { i, id -> it.setLong(i + 1, id) }
            it.executeQuery().use { rs ->
                val out = mutableMapOf<Long, MutableList<OperatingHour>>()
                while (rs.next()) {
                    out.getOrPut(rs.getLong("hotel_id")) { mutableListOf() } +=
                        OperatingHour(rs.getInt("day_of_week"), rs.getTime("opens_at").toLocalTime(), rs.getTime("closes_at").toLocalTime())
                }
                out
            }
        }
    }

    override fun replaceHours(conn: Connection, hotelId: Long, hours: List<OperatingHour>) {
        conn.prepareStatement("DELETE FROM hotel_operating_hours WHERE hotel_id = ?").use { it.setLong(1, hotelId); it.executeUpdate() }
        conn.prepareStatement("INSERT INTO hotel_operating_hours (hotel_id, day_of_week, opens_at, closes_at) VALUES (?, ?, ?, ?)").use {
            hours.forEach { h ->
                it.setLong(1, hotelId); it.setInt(2, h.dayOfWeek)
                it.setTime(3, java.sql.Time.valueOf(h.opensAt)); it.setTime(4, java.sql.Time.valueOf(h.closesAt))
                it.addBatch()
            }
            it.executeBatch()
        }
    }

    override fun categories(conn: Connection, hotelId: Long): List<MenuCategory> =
        conn.prepareStatement("SELECT id, hotel_id, name, sort_order FROM menu_categories WHERE hotel_id = ? ORDER BY sort_order, name").use {
            it.setLong(1, hotelId)
            it.executeQuery().use { rs ->
                generateSequence { if (rs.next()) MenuCategory(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)) else null }.toList()
            }
        }

    override fun createCategory(conn: Connection, hotelId: Long, name: String, sortOrder: Int): MenuCategory {
        val id = conn.prepareStatement("INSERT INTO menu_categories (hotel_id, name, sort_order) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS).use {
            it.setLong(1, hotelId); it.setString(2, name); it.setInt(3, sortOrder)
            it.executeUpdate()
            it.generatedKeys.use { k -> k.next(); k.getLong(1) }
        }
        return MenuCategory(id, hotelId, name, sortOrder)
    }

    override fun categoryBelongsToHotel(conn: Connection, categoryId: Long, hotelId: Long): Boolean =
        conn.prepareStatement("SELECT 1 FROM menu_categories WHERE id = ? AND hotel_id = ?").use {
            it.setLong(1, categoryId); it.setLong(2, hotelId)
            it.executeQuery().use { rs -> rs.next() }
        }

    private val foodCols = "id, hotel_id, category_id, name, description, price, available"

    override fun foodItems(conn: Connection, hotelId: Long): List<FoodItem> =
        conn.prepareStatement("SELECT $foodCols FROM food_items WHERE hotel_id = ? ORDER BY name").use {
            it.setLong(1, hotelId)
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toFood() else null }.toList() }
        }

    override fun findFoodItem(conn: Connection, hotelId: Long, itemId: Long): FoodItem? =
        conn.prepareStatement("SELECT $foodCols FROM food_items WHERE id = ? AND hotel_id = ?").use {
            it.setLong(1, itemId); it.setLong(2, hotelId)
            it.executeQuery().use { rs -> if (rs.next()) rs.toFood() else null }
        }

    override fun createFoodItem(conn: Connection, hotelId: Long, categoryId: Long?, name: String, description: String?, price: BigDecimal, available: Boolean): FoodItem {
        val id = conn.prepareStatement(
            "INSERT INTO food_items (hotel_id, category_id, name, description, price, available) VALUES (?, ?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setLong(1, hotelId); it.setObject(2, categoryId); it.setString(3, name); it.setString(4, description)
            it.setBigDecimal(5, price); it.setBoolean(6, available)
            it.executeUpdate()
            it.generatedKeys.use { k -> k.next(); k.getLong(1) }
        }
        return FoodItem(id, hotelId, categoryId, name, description, price, available)
    }

    override fun updateFoodItem(conn: Connection, hotelId: Long, itemId: Long, categoryId: Long?, name: String, description: String?, price: BigDecimal, available: Boolean): Boolean =
        conn.prepareStatement("UPDATE food_items SET category_id = ?, name = ?, description = ?, price = ?, available = ? WHERE id = ? AND hotel_id = ?").use {
            it.setObject(1, categoryId); it.setString(2, name); it.setString(3, description); it.setBigDecimal(4, price)
            it.setBoolean(5, available); it.setLong(6, itemId); it.setLong(7, hotelId)
            it.executeUpdate() == 1
        }

    override fun setItemAvailability(conn: Connection, hotelId: Long, itemId: Long, available: Boolean): Boolean =
        conn.prepareStatement("UPDATE food_items SET available = ? WHERE id = ? AND hotel_id = ?").use {
            it.setBoolean(1, available); it.setLong(2, itemId); it.setLong(3, hotelId)
            it.executeUpdate() == 1
        }

    override fun deleteFoodItem(conn: Connection, hotelId: Long, itemId: Long): Boolean =
        conn.prepareStatement("DELETE FROM food_items WHERE id = ? AND hotel_id = ?").use {
            it.setLong(1, itemId); it.setLong(2, hotelId)
            it.executeUpdate() == 1
        }

    private fun ResultSet.toHotel() = Hotel(
        getLong("id"), getLong("owner_id"), getString("name"), getString("description"), getString("address"),
        getString("phone"), ApprovalStatus.valueOf(getString("approval_status")), getBoolean("accepting_orders"),
    )

    private fun ResultSet.toFood() = FoodItem(
        getLong("id"), getLong("hotel_id"), getObject("category_id") as Long?, getString("name"), getString("description"),
        getBigDecimal("price"), getBoolean("available"),
    )
}
