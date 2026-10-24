package dev.livin.nammaoorufood.database

import dev.livin.nammaoorufood.hotel.ApprovalStatus
import dev.livin.nammaoorufood.notification.NotificationType
import dev.livin.nammaoorufood.order.OrderStatus
import dev.livin.nammaoorufood.user.Role
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.time
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDateTime

// MySQL DATETIME values are UTC by convention; keep JDBC values independent of the JVM default zone.
private class UtcDateTimeColumnType : ColumnType<LocalDateTime>() {
    override fun sqlType() = "DATETIME"

    override fun notNullValueToDB(value: LocalDateTime): Any = value

    override fun valueFromDB(value: Any): LocalDateTime = when (value) {
        is LocalDateTime -> value
        is Timestamp -> value.toLocalDateTime()
        else -> Timestamp.valueOf(value.toString()).toLocalDateTime()
    }

    override fun nonNullValueToString(value: LocalDateTime): String =
        "'${value.toString().replace('T', ' ')}'"
}

private fun Table.utcDateTime(name: String) = registerColumn(name, UtcDateTimeColumnType())

object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val username = varchar("username", 50).uniqueIndex("uq_users_username")
    val passwordHash = varchar("password_hash", 255)
    val role = customEnumeration(
        "role",
        "ENUM('CUSTOMER','HOTEL_OWNER','ADMIN')",
        { Role.valueOf(it as String) },
        { it.name },
    )
    val displayName = varchar("display_name", 100)
    val phone = varchar("phone", 20).nullable()
    val active = bool("active")
    val createdAt = utcDateTime("created_at")
    val updatedAt = utcDateTime("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object AuthSessionsTable : Table("auth_sessions") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE, fkName = "fk_sessions_user")
    val refreshTokenHash = varchar("refresh_token_hash", 64).uniqueIndex("uq_sessions_refresh_hash")
    val expiresAt = utcDateTime("expires_at")
    val revokedAt = utcDateTime("revoked_at").nullable()
    val createdAt = utcDateTime("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_sessions_user", false, userId)
    }
}

object HotelsTable : Table("hotels") {
    val id = long("id").autoIncrement()
    val ownerId = long("owner_id").references(UsersTable.id, fkName = "fk_hotels_owner")
    val name = varchar("name", 120)
    val description = varchar("description", 500).nullable()
    val address = varchar("address", 255)
    val phone = varchar("phone", 20).nullable()
    val approvalStatus = customEnumeration(
        "approval_status",
        "ENUM('PENDING','APPROVED','REJECTED','SUSPENDED')",
        { ApprovalStatus.valueOf(it as String) },
        { it.name },
    )
    val acceptingOrders = bool("accepting_orders")
    val createdAt = utcDateTime("created_at")
    val updatedAt = utcDateTime("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_hotels_owner", false, ownerId)
        index("idx_hotels_listing", false, approvalStatus, acceptingOrders)
    }
}

object HotelOperatingHoursTable : Table("hotel_operating_hours") {
    val id = long("id").autoIncrement()
    val hotelId = long("hotel_id").references(HotelsTable.id, onDelete = ReferenceOption.CASCADE, fkName = "fk_hours_hotel")
    val dayOfWeek = byte("day_of_week")
    val opensAt = time("opens_at")
    val closesAt = time("closes_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_hours_hotel_day", hotelId, dayOfWeek)
        check("chk_hours_day") { (dayOfWeek greaterEq 1.toByte()) and (dayOfWeek lessEq 7.toByte()) }
    }
}

object MenuCategoriesTable : Table("menu_categories") {
    val id = long("id").autoIncrement()
    val hotelId = long("hotel_id").references(HotelsTable.id, onDelete = ReferenceOption.CASCADE, fkName = "fk_categories_hotel")
    val name = varchar("name", 80)
    val sortOrder = integer("sort_order")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_categories_hotel_name", hotelId, name)
    }
}

object FoodItemsTable : Table("food_items") {
    val id = long("id").autoIncrement()
    val hotelId = long("hotel_id").references(HotelsTable.id, onDelete = ReferenceOption.CASCADE, fkName = "fk_food_hotel")
    val categoryId = long("category_id").references(MenuCategoriesTable.id, onDelete = ReferenceOption.SET_NULL, fkName = "fk_food_category").nullable()
    val name = varchar("name", 120)
    val description = varchar("description", 500).nullable()
    val price = decimal("price", 10, 2)
    val available = bool("available")
    val createdAt = utcDateTime("created_at")
    val updatedAt = utcDateTime("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_food_hotel_available", false, hotelId, available)
        check("chk_food_price") { price greaterEq BigDecimal.ZERO }
    }
}

object OrdersTable : Table("orders") {
    val id = long("id").autoIncrement()
    val customerId = long("customer_id").references(UsersTable.id, fkName = "fk_orders_customer")
    val hotelId = long("hotel_id").references(HotelsTable.id, fkName = "fk_orders_hotel")
    val status = customEnumeration(
        "status",
        "ENUM('PLACED','ACCEPTED','PREPARING','READY_FOR_PICKUP','COMPLETED','REJECTED','CANCELLED')",
        { OrderStatus.valueOf(it as String) },
        { it.name },
    )
    val total = decimal("total", 10, 2)
    val note = varchar("note", 255).nullable()
    val idempotencyKey = varchar("idempotency_key", 64).nullable()
    val createdAt = utcDateTime("created_at")
    val updatedAt = utcDateTime("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_orders_idempotency", customerId, idempotencyKey)
        index("idx_orders_customer", false, customerId, createdAt)
        index("idx_orders_hotel_status", false, hotelId, status, createdAt)
    }
}

object OrderItemsTable : Table("order_items") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id").references(OrdersTable.id, onDelete = ReferenceOption.CASCADE, fkName = "fk_items_order")
    val foodItemId = long("food_item_id").references(FoodItemsTable.id, onDelete = ReferenceOption.SET_NULL, fkName = "fk_items_food").nullable()
    val itemName = varchar("item_name", 120)
    val unitPrice = decimal("unit_price", 10, 2)
    val quantity = integer("quantity")
    val lineTotal = decimal("line_total", 10, 2)

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_items_order", false, orderId)
        check("chk_items_qty") { quantity greater 0 }
    }
}

object HotelTokenCountersTable : Table("hotel_token_counters") {
    val hotelId = long("hotel_id").references(HotelsTable.id, onDelete = ReferenceOption.CASCADE, fkName = "fk_counters_hotel")
    val tokenDate = date("token_date")
    val lastToken = integer("last_token")

    override val primaryKey = PrimaryKey(hotelId, tokenDate)
}

object PickupTokensTable : Table("pickup_tokens") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id").references(OrdersTable.id, fkName = "fk_tokens_order").uniqueIndex("uq_tokens_order")
    val hotelId = long("hotel_id").references(HotelsTable.id, fkName = "fk_tokens_hotel")
    val tokenDate = date("token_date")
    val tokenNumber = integer("token_number")
    val createdAt = utcDateTime("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_tokens_hotel_day_number", hotelId, tokenDate, tokenNumber)
    }
}

object NotificationsTable : Table("notifications") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE, fkName = "fk_notif_user")
    val orderId = long("order_id").references(OrdersTable.id, onDelete = ReferenceOption.CASCADE, fkName = "fk_notif_order")
    val type = customEnumeration(
        "type",
        "ENUM('ORDER_ACCEPTED','ORDER_REJECTED','ORDER_PREPARING','ORDER_READY')",
        { NotificationType.valueOf(it as String) },
        { it.name },
    )
    val title = varchar("title", 120)
    val message = varchar("message", 255)
    val readAt = utcDateTime("read_at").nullable()
    val createdAt = utcDateTime("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_notif_order_type", orderId, type)
        index("idx_notif_user", false, userId, readAt, createdAt)
    }
}
