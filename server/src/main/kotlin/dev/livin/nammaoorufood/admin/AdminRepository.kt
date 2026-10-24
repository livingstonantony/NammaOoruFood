package dev.livin.nammaoorufood.admin

import dev.livin.nammaoorufood.database.HotelsTable
import dev.livin.nammaoorufood.database.OrdersTable
import dev.livin.nammaoorufood.database.UsersTable
import dev.livin.nammaoorufood.order.OrderStatus
import dev.livin.nammaoorufood.user.Role
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.ResultRow
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
    fun hotelSales(conn: Connection, from: Instant, to: Instant): List<HotelSalesRaw>
    fun customerCounts(conn: Connection): Pair<Int, Int>
    fun hotelCounts(conn: Connection): Pair<Int, Int>
}

private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

class ExposedAdminRepository : AdminRepository {
    private fun customerConditions(query: String?, active: Boolean?): List<Op<Boolean>> =
        buildList {
            add(UsersTable.role eq Role.CUSTOMER)
            if (!query.isNullOrBlank()) {
                val pattern = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                add(
                    ((UsersTable.username like pattern) or (UsersTable.displayName like pattern)) or
                        (UsersTable.phone like pattern),
                )
            }
            if (active != null) add(UsersTable.active eq active)
        }

    private fun customerQuery(query: String?, active: Boolean?) =
        UsersTable.selectAll().where { customerConditions(query, active).reduce { left, right -> left and right } }

    override fun searchCustomers(conn: Connection, query: String?, active: Boolean?, limit: Int, offset: Int): List<CustomerDto> {
        val customers = customerQuery(query, active)
            .orderBy(UsersTable.createdAt to SortOrder.DESC, UsersTable.id to SortOrder.DESC)
            .limit(limit).offset(offset.toLong())
            .toList()
        if (customers.isEmpty()) return emptyList()
        val ids = customers.map { it[UsersTable.id] }
        val countsByCustomer = OrdersTable.select(OrdersTable.customerId, OrdersTable.id.count())
            .where { OrdersTable.customerId inList ids }
            .groupBy(OrdersTable.customerId)
            .associate { it[OrdersTable.customerId] to it[OrdersTable.id.count()].toInt() }
        return customers.map { it.toCustomer(countsByCustomer[it[UsersTable.id]] ?: 0) }
    }

    override fun countCustomers(conn: Connection, query: String?, active: Boolean?): Int =
        customerQuery(query, active).count().toInt()

    override fun findCustomer(conn: Connection, id: Long): CustomerDto? {
        val customer = UsersTable.selectAll().where {
            (UsersTable.role eq Role.CUSTOMER) and (UsersTable.id eq id)
        }.limit(1).singleOrNull() ?: return null
        val orderCount = OrdersTable.selectAll().where { OrdersTable.customerId eq id }.count().toInt()
        return customer.toCustomer(orderCount)
    }

    override fun setCustomerActive(conn: Connection, id: Long, active: Boolean) {
        UsersTable.update({
            (UsersTable.id eq id) and (UsersTable.role eq Role.CUSTOMER)
        }) {
            it[UsersTable.active] = active
        }
    }

    override fun hotelSales(conn: Connection, from: Instant, to: Instant): List<HotelSalesRaw> {
        val start = from.utc()
        val end = to.utc()
        val placed = OrdersTable.select(OrdersTable.hotelId, OrdersTable.id.count())
            .where { (OrdersTable.createdAt greaterEq start) and (OrdersTable.createdAt less end) }
            .groupBy(OrdersTable.hotelId)
            .associate { it[OrdersTable.hotelId] to it[OrdersTable.id.count()].toInt() }
        val completedOrders = OrdersTable.select(OrdersTable.hotelId, OrdersTable.id.count())
            .where {
                (OrdersTable.status eq OrderStatus.COMPLETED) and
                    (OrdersTable.updatedAt greaterEq start) and (OrdersTable.updatedAt less end)
            }
            .groupBy(OrdersTable.hotelId)
            .associate { it[OrdersTable.hotelId] to it[OrdersTable.id.count()].toInt() }
        val rejectedOrders = OrdersTable.select(OrdersTable.hotelId, OrdersTable.id.count())
            .where {
                (OrdersTable.status eq OrderStatus.REJECTED) and
                    (OrdersTable.updatedAt greaterEq start) and (OrdersTable.updatedAt less end)
            }
            .groupBy(OrdersTable.hotelId)
            .associate { it[OrdersTable.hotelId] to it[OrdersTable.id.count()].toInt() }
        val sales = OrdersTable.select(OrdersTable.hotelId, OrdersTable.total.sum())
            .where {
                (OrdersTable.status eq OrderStatus.COMPLETED) and
                    (OrdersTable.updatedAt greaterEq start) and (OrdersTable.updatedAt less end)
            }
            .groupBy(OrdersTable.hotelId)
            .associate { it[OrdersTable.hotelId] to it[OrdersTable.total.sum()] }

        return HotelsTable.selectAll()
            .map { row ->
                val hotelId = row[HotelsTable.id]
                HotelSalesRaw(
                    hotelId,
                    row[HotelsTable.name],
                    placed[hotelId] ?: 0,
                    completedOrders[hotelId] ?: 0,
                    rejectedOrders[hotelId] ?: 0,
                    sales[hotelId] ?: BigDecimal.ZERO,
                )
            }
            .sortedWith(compareByDescending<HotelSalesRaw> { it.sales }.thenBy { it.hotelName })
    }

    override fun customerCounts(conn: Connection): Pair<Int, Int> {
        val customerRows = UsersTable.select(UsersTable.active)
            .where { UsersTable.role eq Role.CUSTOMER }
            .toList()
        return customerRows.size to customerRows.count { it[UsersTable.active] }
    }

    override fun hotelCounts(conn: Connection): Pair<Int, Int> {
        val statuses = HotelsTable.select(HotelsTable.approvalStatus).toList().map { it[HotelsTable.approvalStatus] }
        return statuses.count { it == dev.livin.nammaoorufood.hotel.ApprovalStatus.APPROVED } to
            statuses.count { it == dev.livin.nammaoorufood.hotel.ApprovalStatus.PENDING }
    }

    private fun ResultRow.toCustomer(orderCount: Int) = CustomerDto(
        this[UsersTable.id],
        this[UsersTable.username],
        this[UsersTable.displayName],
        this[UsersTable.phone],
        this[UsersTable.active],
        this[UsersTable.createdAt].toInstant(ZoneOffset.UTC).toString(),
        orderCount,
    )
}
