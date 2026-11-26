package dev.livin.nammaoorufood.hotel

import dev.livin.nammaoorufood.database.FoodItemsTable
import dev.livin.nammaoorufood.database.HotelOperatingHoursTable
import dev.livin.nammaoorufood.database.HotelsTable
import dev.livin.nammaoorufood.database.MenuCategoriesTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.ResultRow
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalTime

interface HotelRepository {
    fun create(conn: Connection, ownerId: Long, name: String, description: String?, address: String, phone: String?, imageUrl: String?): Hotel
    fun update(conn: Connection, id: Long, name: String, description: String?, address: String, phone: String?, imageUrl: String?)
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
    fun createFoodItem(conn: Connection, hotelId: Long, categoryId: Long?, name: String, description: String?, price: BigDecimal, available: Boolean, quantityAvailable: Int? = null, imageUrl: String? = null): FoodItem
    fun updateFoodItem(conn: Connection, hotelId: Long, itemId: Long, categoryId: Long?, name: String, description: String?, price: BigDecimal, available: Boolean, quantityAvailable: Int? = null, imageUrl: String? = null): Boolean
    fun setItemAvailability(conn: Connection, hotelId: Long, itemId: Long, available: Boolean? = null, quantityAvailable: Int? = null, updateQuantity: Boolean = false): Boolean
    fun decrementStock(conn: Connection, hotelId: Long, itemId: Long, amount: Int): Boolean
    fun deleteFoodItem(conn: Connection, hotelId: Long, itemId: Long): Boolean
}

class ExposedHotelRepository : HotelRepository {
    override fun create(conn: Connection, ownerId: Long, name: String, description: String?, address: String, phone: String?, imageUrl: String?): Hotel {
        val id = HotelsTable.insert {
            it[HotelsTable.ownerId] = ownerId
            it[HotelsTable.name] = name
            it[HotelsTable.description] = description
            it[HotelsTable.address] = address
            it[HotelsTable.phone] = phone
            it[HotelsTable.imageUrl] = imageUrl
        }[HotelsTable.id]
        return Hotel(id, ownerId, name, description, address, phone, ApprovalStatus.PENDING, false, imageUrl)
    }

    override fun update(conn: Connection, id: Long, name: String, description: String?, address: String, phone: String?, imageUrl: String?) {
        HotelsTable.update({ HotelsTable.id eq id }) {
            it[HotelsTable.name] = name
            it[HotelsTable.description] = description
            it[HotelsTable.address] = address
            it[HotelsTable.phone] = phone
            it[HotelsTable.imageUrl] = imageUrl
        }
    }

    override fun findById(conn: Connection, id: Long): Hotel? =
        HotelsTable.selectAll().where { HotelsTable.id eq id }.limit(1).singleOrNull()?.toHotel()

    override fun search(conn: Connection, query: String?, approvedOnly: Boolean, ownerId: Long?): List<Hotel> {
        val conditions = buildList<Op<Boolean>> {
            if (approvedOnly) add(HotelsTable.approvalStatus eq ApprovalStatus.APPROVED)
            if (ownerId != null) add(HotelsTable.ownerId eq ownerId)
            if (!query.isNullOrBlank()) {
                val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                add(HotelsTable.name like "%$escaped%")
            }
        }
        val queryRows = HotelsTable.selectAll().apply {
            if (conditions.isNotEmpty()) where { conditions.reduce { left, right -> left and right } }
        }
        return queryRows.orderBy(HotelsTable.name to SortOrder.ASC).map { it.toHotel() }
    }

    override fun setApproval(conn: Connection, id: Long, status: ApprovalStatus) {
        HotelsTable.update({ HotelsTable.id eq id }) {
            it[approvalStatus] = status
            if (status != ApprovalStatus.APPROVED) it[acceptingOrders] = false
        }
    }

    override fun setAcceptingOrders(conn: Connection, id: Long, accepting: Boolean) {
        HotelsTable.update({ HotelsTable.id eq id }) { it[acceptingOrders] = accepting }
    }

    override fun hoursFor(conn: Connection, hotelIds: Collection<Long>): Map<Long, List<OperatingHour>> {
        if (hotelIds.isEmpty()) return emptyMap()
        return HotelOperatingHoursTable.selectAll()
            .where { HotelOperatingHoursTable.hotelId inList hotelIds }
            .orderBy(HotelOperatingHoursTable.dayOfWeek to SortOrder.ASC)
            .map { row ->
                row[HotelOperatingHoursTable.hotelId] to OperatingHour(
                    row[HotelOperatingHoursTable.dayOfWeek].toInt(),
                    row[HotelOperatingHoursTable.opensAt],
                    row[HotelOperatingHoursTable.closesAt],
                )
            }
            .groupBy({ it.first }, { it.second })
    }

    override fun replaceHours(conn: Connection, hotelId: Long, hours: List<OperatingHour>) {
        HotelOperatingHoursTable.deleteWhere { HotelOperatingHoursTable.hotelId eq hotelId }
        HotelOperatingHoursTable.batchInsert(hours) { hour ->
            this[HotelOperatingHoursTable.hotelId] = hotelId
            this[HotelOperatingHoursTable.dayOfWeek] = hour.dayOfWeek.toByte()
            this[HotelOperatingHoursTable.opensAt] = hour.opensAt
            this[HotelOperatingHoursTable.closesAt] = hour.closesAt
        }
    }

    override fun categories(conn: Connection, hotelId: Long): List<MenuCategory> =
        MenuCategoriesTable.selectAll()
            .where { MenuCategoriesTable.hotelId eq hotelId }
            .orderBy(MenuCategoriesTable.sortOrder to SortOrder.ASC, MenuCategoriesTable.name to SortOrder.ASC)
            .map {
                MenuCategory(it[MenuCategoriesTable.id], it[MenuCategoriesTable.hotelId], it[MenuCategoriesTable.name], it[MenuCategoriesTable.sortOrder])
            }

    override fun createCategory(conn: Connection, hotelId: Long, name: String, sortOrder: Int): MenuCategory {
        val id = MenuCategoriesTable.insert {
            it[MenuCategoriesTable.hotelId] = hotelId
            it[MenuCategoriesTable.name] = name
            it[MenuCategoriesTable.sortOrder] = sortOrder
        }[MenuCategoriesTable.id]
        return MenuCategory(id, hotelId, name, sortOrder)
    }

    override fun categoryBelongsToHotel(conn: Connection, categoryId: Long, hotelId: Long): Boolean =
        MenuCategoriesTable.selectAll().where {
            (MenuCategoriesTable.id eq categoryId) and (MenuCategoriesTable.hotelId eq hotelId)
        }.limit(1).singleOrNull() != null

    override fun foodItems(conn: Connection, hotelId: Long): List<FoodItem> =
        FoodItemsTable.selectAll().where { FoodItemsTable.hotelId eq hotelId }
            .orderBy(FoodItemsTable.name to SortOrder.ASC)
            .map { it.toFoodItem() }

    override fun findFoodItem(conn: Connection, hotelId: Long, itemId: Long): FoodItem? =
        FoodItemsTable.selectAll().where {
            (FoodItemsTable.id eq itemId) and (FoodItemsTable.hotelId eq hotelId)
        }.limit(1).singleOrNull()?.toFoodItem()

    override fun createFoodItem(
        conn: Connection,
        hotelId: Long,
        categoryId: Long?,
        name: String,
        description: String?,
        price: BigDecimal,
        available: Boolean,
        quantityAvailable: Int?,
        imageUrl: String?,
    ): FoodItem {
        val id = FoodItemsTable.insert {
            it[FoodItemsTable.hotelId] = hotelId
            it[FoodItemsTable.categoryId] = categoryId
            it[FoodItemsTable.name] = name
            it[FoodItemsTable.description] = description
            it[FoodItemsTable.price] = price
            it[FoodItemsTable.available] = available
            it[FoodItemsTable.quantityAvailable] = quantityAvailable
            it[FoodItemsTable.imageUrl] = imageUrl
        }[FoodItemsTable.id]
        return FoodItem(id, hotelId, categoryId, name, description, price, available, quantityAvailable, imageUrl)
    }

    override fun updateFoodItem(
        conn: Connection,
        hotelId: Long,
        itemId: Long,
        categoryId: Long?,
        name: String,
        description: String?,
        price: BigDecimal,
        available: Boolean,
        quantityAvailable: Int?,
        imageUrl: String?,
    ): Boolean = FoodItemsTable.update({
        (FoodItemsTable.id eq itemId) and (FoodItemsTable.hotelId eq hotelId)
    }) {
        it[FoodItemsTable.categoryId] = categoryId
        it[FoodItemsTable.name] = name
        it[FoodItemsTable.description] = description
        it[FoodItemsTable.price] = price
        it[FoodItemsTable.available] = available
        it[FoodItemsTable.quantityAvailable] = quantityAvailable
        it[FoodItemsTable.imageUrl] = imageUrl
    } == 1

    override fun setItemAvailability(conn: Connection, hotelId: Long, itemId: Long, available: Boolean?, quantityAvailable: Int?, updateQuantity: Boolean): Boolean =
        FoodItemsTable.update({
            (FoodItemsTable.id eq itemId) and (FoodItemsTable.hotelId eq hotelId)
        }) { row ->
            if (available != null) row[FoodItemsTable.available] = available
            if (updateQuantity) {
                row[FoodItemsTable.quantityAvailable] = quantityAvailable
                if (quantityAvailable != null && quantityAvailable > 0 && available == null) {
                    row[FoodItemsTable.available] = true
                }
            } else if (quantityAvailable != null) {
                row[FoodItemsTable.quantityAvailable] = quantityAvailable
                if (quantityAvailable > 0 && available == null) {
                    row[FoodItemsTable.available] = true
                }
            }
        } == 1

    override fun decrementStock(conn: Connection, hotelId: Long, itemId: Long, amount: Int): Boolean {
        val item = findFoodItem(conn, hotelId, itemId) ?: return false
        val current = item.quantityAvailable ?: return true // Unlimited stock
        val updated = (current - amount).coerceAtLeast(0)
        val isNowAvailable = updated > 0
        return FoodItemsTable.update({
            (FoodItemsTable.id eq itemId) and (FoodItemsTable.hotelId eq hotelId)
        }) {
            it[FoodItemsTable.quantityAvailable] = updated
            if (!isNowAvailable) {
                it[FoodItemsTable.available] = false
            }
        } == 1
    }

    override fun deleteFoodItem(conn: Connection, hotelId: Long, itemId: Long): Boolean =
        FoodItemsTable.deleteWhere {
            (FoodItemsTable.id eq itemId) and (FoodItemsTable.hotelId eq hotelId)
        } == 1

    private fun ResultRow.toHotel() = Hotel(
        this[HotelsTable.id],
        this[HotelsTable.ownerId],
        this[HotelsTable.name],
        this[HotelsTable.description],
        this[HotelsTable.address],
        this[HotelsTable.phone],
        this[HotelsTable.approvalStatus],
        this[HotelsTable.acceptingOrders],
        this[HotelsTable.imageUrl],
    )

    private fun ResultRow.toFoodItem() = FoodItem(
        this[FoodItemsTable.id],
        this[FoodItemsTable.hotelId],
        this[FoodItemsTable.categoryId],
        this[FoodItemsTable.name],
        this[FoodItemsTable.description],
        this[FoodItemsTable.price],
        this[FoodItemsTable.available],
        this[FoodItemsTable.quantityAvailable],
        this[FoodItemsTable.imageUrl],
    )
}
