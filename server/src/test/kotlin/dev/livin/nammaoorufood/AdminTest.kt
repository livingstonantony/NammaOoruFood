package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.admin.*
import dev.livin.nammaoorufood.auth.AuthResponse
import dev.livin.nammaoorufood.auth.LoginRequest
import dev.livin.nammaoorufood.auth.RegisterRequest
import dev.livin.nammaoorufood.config.AppConfig
import dev.livin.nammaoorufood.hotel.*
import dev.livin.nammaoorufood.order.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.*

class AdminTest {
    private fun config() = AppConfig.fromEnv(
        System.getenv() + mapOf(
            "APP_ENV" to "test",
            "DB_URL" to "jdbc:mysql://localhost:3306/nammaoorufood_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            "LOGIN_RATE_LIMIT_PER_MINUTE" to "1000",
        ),
    )

    @BeforeTest
    fun cleanDb() {
        val c = config().database
        DriverManager.getConnection(c.url, c.user, c.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("SET FOREIGN_KEY_CHECKS = 0")
                listOf("notifications", "auth_sessions", "hotel_operating_hours", "menu_categories", "pickup_tokens", "hotel_token_counters", "order_items", "orders", "food_items", "hotels", "users")
                    .forEach { st.execute("TRUNCATE TABLE $it") }
                st.execute("SET FOREIGN_KEY_CHECKS = 1")
            }
        }
    }

    private class Fx(val c: HttpClient, val owner: String, val admin: String, val customer: String, val hotel: HotelDto, val item: FoodItemDto)

    private suspend inline fun <reified T> HttpClient.send(method: HttpMethod, path: String, token: String, body: T? = null) =
        request(path) {
            this.method = method
            bearerAuth(token)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private suspend fun HttpClient.token(user: String, pw: String) =
        post("/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(user, pw)) }.body<AuthResponse>().accessToken

    private suspend fun ApplicationTestBuilder.fixture(): Fx {
        application { module(config()) }
        val c = createClient { install(ContentNegotiation) { json() } }
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val customer = c.token("customer", "customer12345")
        val hotel = c.send(HttpMethod.Post, "/owner/hotels", owner, HotelRequest("Amma Hotel", null, "Main St")).body<HotelDto>()
        c.send(HttpMethod.Patch, "/admin/hotels/${hotel.id}/approval", admin, ApprovalRequest(ApprovalStatus.APPROVED))
        c.send(HttpMethod.Put, "/owner/hotels/${hotel.id}/hours", owner, OperatingHoursRequest((1..7).map { OperatingHourDto(it, "00:00", "23:59") }))
        c.send(HttpMethod.Patch, "/owner/hotels/${hotel.id}/accepting-orders", owner, AcceptingOrdersRequest(true))
        val item = c.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/items", owner, FoodItemRequest("Parotta", null, "15.00")).body<FoodItemDto>()
        return Fx(c, owner, admin, customer, hotel, item)
    }

    private suspend fun Fx.place(qty: Int = 1) =
        c.send(HttpMethod.Post, "/orders", customer, PlaceOrderRequest(hotel.id, listOf(OrderLineRequest(item.id, qty)))).body<OrderDto>()

    private suspend fun Fx.complete(id: Long) {
        c.send<Unit>(HttpMethod.Post, "/orders/$id/accept", owner)
        for (s in listOf(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP, OrderStatus.COMPLETED)) c.send(HttpMethod.Patch, "/orders/$id/status", owner, StatusUpdateRequest(s))
    }

    @Test
    fun adminEndpointsRejectNonAdmins() = testApplication {
        val f = fixture()
        val paths = listOf("/admin/customers", "/admin/orders", "/admin/reports/overview", "/admin/reports/hotels")
        for (p in paths) {
            assertEquals(HttpStatusCode.Forbidden, f.c.send<Unit>(HttpMethod.Get, p, f.customer).status, p)
            assertEquals(HttpStatusCode.Forbidden, f.c.send<Unit>(HttpMethod.Get, p, f.owner).status, p)
            assertEquals(HttpStatusCode.Unauthorized, f.c.get(p).status, p)
        }
        assertEquals(HttpStatusCode.Forbidden, f.c.send(HttpMethod.Patch, "/admin/customers/1/active", f.owner, ActiveRequest(false)).status)
    }

    @Test
    fun customerSearchPaginationAndFilter() = testApplication {
        val f = fixture()
        for (n in listOf("meena", "murugan")) {
            f.c.post("/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest(n, "password123", "Name $n")) }
        }
        val all = f.c.send<Unit>(HttpMethod.Get, "/admin/customers", f.admin).body<CustomerPage>()
        assertEquals(3, all.total) // dev customer + 2 (owner/admin excluded)
        assertTrue(all.items.none { it.username == "owner" || it.username == "admin" })
        assertEquals(3, f.c.send<Unit>(HttpMethod.Get, "/admin/customers?q=m", f.admin).body<CustomerPage>().total)
        assertEquals(1, f.c.send<Unit>(HttpMethod.Get, "/admin/customers?q=murugan", f.admin).body<CustomerPage>().total)
        assertEquals(0, f.c.send<Unit>(HttpMethod.Get, "/admin/customers?q=%25", f.admin).body<CustomerPage>().total)
        assertEquals(1, f.c.send<Unit>(HttpMethod.Get, "/admin/customers?page=2&pageSize=2", f.admin).body<CustomerPage>().items.size)
    }

    @Test
    fun deactivatingCustomerRevokesSessionsAndBlocksLoginUntilReactivated() = testApplication {
        val f = fixture()
        val id = f.c.send<Unit>(HttpMethod.Get, "/admin/customers?q=customer", f.admin).body<CustomerPage>().items.single().id
        f.place()

        val r = f.c.send(HttpMethod.Patch, "/admin/customers/$id/active", f.admin, ActiveRequest(false))
        assertEquals(HttpStatusCode.OK, r.status)
        assertFalse(r.body<CustomerDto>().active)
        assertEquals(1, r.body<CustomerDto>().orderCount)

        assertEquals(HttpStatusCode.Unauthorized, f.c.send<Unit>(HttpMethod.Get, "/auth/me", f.customer).status)
        val login = f.c.post("/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest("customer", "customer12345")) }
        assertEquals(HttpStatusCode.Unauthorized, login.status)

        f.c.send(HttpMethod.Patch, "/admin/customers/$id/active", f.admin, ActiveRequest(true))
        assertEquals(HttpStatusCode.OK, f.c.post("/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest("customer", "customer12345")) }.status)
    }

    @Test
    fun cannotDeactivateNonCustomersOrUnknownIds() = testApplication {
        val f = fixture()
        val database = config().database
        val adminId = DriverManager.getConnection(database.url, database.user, database.password).use { c ->
            c.createStatement().executeQuery("SELECT id FROM users WHERE username='admin'").use { it.next(); it.getLong(1) }
        }
        assertEquals(HttpStatusCode.NotFound, f.c.send(HttpMethod.Patch, "/admin/customers/$adminId/active", f.admin, ActiveRequest(false)).status)
        assertEquals(HttpStatusCode.NotFound, f.c.send(HttpMethod.Patch, "/admin/customers/999999/active", f.admin, ActiveRequest(false)).status)
        assertEquals(HttpStatusCode.OK, f.c.send<Unit>(HttpMethod.Get, "/auth/me", f.admin).status)
    }

    @Test
    fun orderMonitoringFilters() = testApplication {
        val f = fixture()
        val a = f.place(); f.place(); f.place()
        f.c.send<Unit>(HttpMethod.Post, "/orders/${a.id}/reject", f.owner)
        assertEquals(3, f.c.send<Unit>(HttpMethod.Get, "/admin/orders", f.admin).body<OrderPage>().total)
        assertEquals(1, f.c.send<Unit>(HttpMethod.Get, "/admin/orders?status=REJECTED", f.admin).body<OrderPage>().total)
        assertEquals(3, f.c.send<Unit>(HttpMethod.Get, "/admin/orders?hotelId=${f.hotel.id}", f.admin).body<OrderPage>().total)
        assertEquals(0, f.c.send<Unit>(HttpMethod.Get, "/admin/orders?hotelId=999999", f.admin).body<OrderPage>().total)
        assertEquals(2, f.c.send<Unit>(HttpMethod.Get, "/admin/orders?pageSize=2", f.admin).body<OrderPage>().items.size)
        val today = LocalDate.now(BUSINESS_ZONE)
        assertEquals(3, f.c.send<Unit>(HttpMethod.Get, "/admin/orders?from=$today&to=$today", f.admin).body<OrderPage>().total)
        assertEquals(0, f.c.send<Unit>(HttpMethod.Get, "/admin/orders?from=${today.minusDays(10)}&to=${today.minusDays(5)}", f.admin).body<OrderPage>().total)
        assertEquals(HttpStatusCode.BadRequest, f.c.send<Unit>(HttpMethod.Get, "/admin/orders?status=BAD", f.admin).status)
        assertEquals(HttpStatusCode.BadRequest, f.c.send<Unit>(HttpMethod.Get, "/admin/orders?from=nope", f.admin).status)
    }

    @Test
    fun reportsAggregateSalesPerHotelAndPlatform() = testApplication {
        val f = fixture()
        val done = f.place(2) // 30.00
        val done2 = f.place(1) // 15.00
        val rejected = f.place()
        f.place() // stays placed
        f.complete(done.id); f.complete(done2.id)
        f.c.send<Unit>(HttpMethod.Post, "/orders/${rejected.id}/reject", f.owner)

        val hotels = f.c.send<Unit>(HttpMethod.Get, "/admin/reports/hotels", f.admin).body<HotelSalesReport>()
        val row = hotels.rows.single()
        assertEquals(4, row.ordersPlaced)
        assertEquals(2, row.completedOrders)
        assertEquals(1, row.rejectedOrders)
        assertEquals("45.00", row.sales)

        val o = f.c.send<Unit>(HttpMethod.Get, "/admin/reports/overview", f.admin).body<PlatformOverview>()
        assertEquals("45.00", o.totalSales)
        assertEquals(4, o.ordersPlaced)
        assertEquals(1, o.totalCustomers)
        assertEquals(1, o.approvedHotels)
        assertEquals(0, o.pendingHotels)

        val past = LocalDate.now(BUSINESS_ZONE).minusDays(30)
        val empty = f.c.send<Unit>(HttpMethod.Get, "/admin/reports/overview?from=$past&to=${past.plusDays(1)}", f.admin).body<PlatformOverview>()
        assertEquals("0.00", empty.totalSales)
        assertEquals(0, empty.ordersPlaced)
    }

    @Test
    fun reportRangeValidation() = testApplication {
        val f = fixture()
        assertEquals(HttpStatusCode.BadRequest, f.c.send<Unit>(HttpMethod.Get, "/admin/reports/overview?from=2026-02-01&to=2026-01-01", f.admin).status)
        assertEquals(HttpStatusCode.BadRequest, f.c.send<Unit>(HttpMethod.Get, "/admin/reports/hotels?from=2020-01-01&to=2026-01-01", f.admin).status)
        assertEquals(HttpStatusCode.BadRequest, f.c.send<Unit>(HttpMethod.Get, "/admin/reports/hotels?from=garbage", f.admin).status)
    }
}
