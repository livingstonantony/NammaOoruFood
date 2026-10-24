package dev.livin.nammaoorufood.admin

import dev.livin.nammaoorufood.auth.JWT_AUTH
import dev.livin.nammaoorufood.auth.SessionRepository
import dev.livin.nammaoorufood.auth.UserPrincipal
import dev.livin.nammaoorufood.auth.principalOrThrow
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.hotel.BUSINESS_ZONE
import dev.livin.nammaoorufood.order.*
import dev.livin.nammaoorufood.plugins.ForbiddenException
import dev.livin.nammaoorufood.plugins.NotFoundException
import dev.livin.nammaoorufood.plugins.ValidationException
import dev.livin.nammaoorufood.user.Role
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

class AdminService(
    private val db: Database,
    private val admin: AdminRepository,
    private val orders: OrderRepository,
    private val sessions: SessionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun customers(p: UserPrincipal, query: String?, active: Boolean?, page: Int, pageSize: Int): CustomerPage {
        requireAdmin(p)
        val size = pageSize.coerceIn(1, 100)
        val pageNo = page.coerceAtLeast(1)
        return db.transaction { conn ->
            CustomerPage(admin.searchCustomers(conn, query, active, size, (pageNo - 1) * size), pageNo, size, admin.countCustomers(conn, query, active))
        }
    }

    // Deactivation also revokes every session so the customer is signed out immediately.
    fun setCustomerActive(p: UserPrincipal, id: Long, active: Boolean): CustomerDto {
        requireAdmin(p)
        return db.transaction { conn ->
            admin.findCustomer(conn, id) ?: throw NotFoundException("Customer not found")
            admin.setCustomerActive(conn, id, active)
            if (!active) sessions.revokeAllForUser(conn, id, clock.instant())
            admin.findCustomer(conn, id)!!
        }
    }

    fun orders(p: UserPrincipal, filter: AdminOrderFilter, page: Int, pageSize: Int): OrderPage {
        requireAdmin(p)
        val size = pageSize.coerceIn(1, 100)
        val pageNo = page.coerceAtLeast(1)
        return db.transaction { conn ->
            OrderPage(orders.listForAdmin(conn, filter, size, (pageNo - 1) * size).map { it.toDto() }, pageNo, size, orders.countForAdmin(conn, filter))
        }
    }

    fun hotelSales(p: UserPrincipal, from: LocalDate?, to: LocalDate?): HotelSalesReport {
        requireAdmin(p)
        val range = resolveRange(from, to)
        val rows = db.transaction { admin.hotelSales(it, range.start, range.endExclusive) }
        return HotelSalesReport(range.from.toString(), range.to.toString(), rows.map { it.toRow() })
    }

    fun overview(p: UserPrincipal, from: LocalDate?, to: LocalDate?): PlatformOverview {
        requireAdmin(p)
        val range = resolveRange(from, to)
        return db.transaction { conn ->
            val rows = admin.hotelSales(conn, range.start, range.endExclusive)
            val (customers, activeCustomers) = admin.customerCounts(conn)
            val (approved, pending) = admin.hotelCounts(conn)
            PlatformOverview(
                range.from.toString(), range.to.toString(),
                ordersPlaced = rows.sumOf { it.placed },
                completedOrders = rows.sumOf { it.completed },
                rejectedOrders = rows.sumOf { it.rejected },
                totalSales = rows.fold(BigDecimal.ZERO) { a, r -> a + r.sales }.setScale(2).toPlainString(),
                totalCustomers = customers, activeCustomers = activeCustomers, approvedHotels = approved, pendingHotels = pending,
            )
        }
    }

    private class Range(val from: LocalDate, val to: LocalDate, val start: Instant, val endExclusive: Instant)

    // Dates are business-zone calendar days; both ends are inclusive. Defaults to today.
    private fun resolveRange(from: LocalDate?, to: LocalDate?): Range {
        val today = clock.instant().atZone(BUSINESS_ZONE).toLocalDate()
        val f = from ?: to ?: today
        val t = to ?: f
        if (t.isBefore(f)) throw ValidationException("'to' must not be before 'from'")
        if (ChronoUnit.DAYS.between(f, t) > 366) throw ValidationException("Date range must not exceed 366 days")
        return Range(f, t, f.atStartOfDay(BUSINESS_ZONE).toInstant(), t.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant())
    }

    private fun HotelSalesRaw.toRow() = HotelSalesRow(hotelId, hotelName, placed, completed, rejected, sales.setScale(2).toPlainString())

    private fun requireAdmin(p: UserPrincipal) {
        if (p.role != Role.ADMIN) throw ForbiddenException()
    }

    fun resolveFilter(q: Parameters): AdminOrderFilter {
        val range = if (q["from"] != null || q["to"] != null) resolveRange(parseDate(q["from"]), parseDate(q["to"])) else null
        return AdminOrderFilter(
            status = q["status"]?.let { s -> OrderStatus.entries.firstOrNull { it.name == s } ?: throw ValidationException("Invalid status") },
            hotelId = q["hotelId"]?.let { it.toLongOrNull() ?: throw ValidationException("Invalid hotelId") },
            customerId = q["customerId"]?.let { it.toLongOrNull() ?: throw ValidationException("Invalid customerId") },
            from = range?.start,
            to = range?.endExclusive,
        )
    }

    fun parseDate(s: String?): LocalDate? = s?.let {
        try { LocalDate.parse(it) } catch (e: DateTimeParseException) { throw ValidationException("Invalid date '$it', expected yyyy-MM-dd") }
    }
}

fun Route.adminRoutes(service: AdminService) {
    authenticate(JWT_AUTH) {
        route("/admin") {
            get("/customers") {
                val q = call.request.queryParameters
                call.respond(service.customers(call.principalOrThrow(), q["q"], q["active"]?.toBooleanStrictOrNull(), q["page"]?.toIntOrNull() ?: 1, q["pageSize"]?.toIntOrNull() ?: 20))
            }
            patch("/customers/{id}/active") {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid id")
                call.respond(service.setCustomerActive(call.principalOrThrow(), id, call.receive<ActiveRequest>().active))
            }
            get("/orders") {
                val q = call.request.queryParameters
                val p = call.principalOrThrow()
                if (p.role != Role.ADMIN) throw ForbiddenException()
                call.respond(service.orders(p, service.resolveFilter(q), q["page"]?.toIntOrNull() ?: 1, q["pageSize"]?.toIntOrNull() ?: 20))
            }
            get("/reports/overview") {
                val q = call.request.queryParameters
                call.respond(service.overview(call.principalOrThrow(), service.parseDate(q["from"]), service.parseDate(q["to"])))
            }
            get("/reports/hotels") {
                val q = call.request.queryParameters
                call.respond(service.hotelSales(call.principalOrThrow(), service.parseDate(q["from"]), service.parseDate(q["to"])))
            }
        }
    }
}
