package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.auth.AuthResponse
import dev.livin.nammaoorufood.auth.LoginRequest
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.sql.DriverManager
import kotlin.test.*

class OrderStateMachineTest {
    @Test
    fun onlyForwardTransitionsAreAllowed() {
        val s = OrderStatus.entries
        val valid = setOf(
            OrderStatus.PLACED to OrderStatus.ACCEPTED, OrderStatus.PLACED to OrderStatus.REJECTED, OrderStatus.PLACED to OrderStatus.CANCELLED,
            OrderStatus.ACCEPTED to OrderStatus.PREPARING, OrderStatus.PREPARING to OrderStatus.READY_FOR_PICKUP,
            OrderStatus.READY_FOR_PICKUP to OrderStatus.COMPLETED,
        )
        for (from in s) for (to in s) assertEquals((from to to) in valid, OrderStateMachine.canTransition(from, to), "$from -> $to")
    }
}

class OwnerOrderTest {
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

    private suspend fun Fx.act(token: String, id: Long, action: String) = c.send<Unit>(HttpMethod.Post, "/orders/$id/$action", token)

    private suspend fun Fx.status(token: String, id: Long, s: OrderStatus) =
        c.send(HttpMethod.Patch, "/orders/$id/status", token, StatusUpdateRequest(s))

    @Test
    fun fullLifecycleAssignsTokenOnAcceptance() = testApplication {
        val f = fixture()
        val o = f.place(2)
        assertNull(o.pickupToken)

        val accepted = f.act(f.owner, o.id, "accept")
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals(1, accepted.body<OrderDto>().pickupToken)

        for (s in listOf(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP, OrderStatus.COMPLETED)) {
            val r = f.status(f.owner, o.id, s)
            assertEquals(HttpStatusCode.OK, r.status, "to $s")
            assertEquals(s, r.body<OrderDto>().status)
        }
        val seen = f.c.send<Unit>(HttpMethod.Get, "/orders/${o.id}", f.customer).body<OrderDto>()
        assertEquals(OrderStatus.COMPLETED, seen.status)
        assertEquals(1, seen.pickupToken)
    }

    @Test
    fun tokensAreSequentialPerHotelAndRejectedOrdersGetNone() = testApplication {
        val f = fixture()
        val a = f.place(); val b = f.place(); val c3 = f.place()
        assertEquals(1, f.act(f.owner, a.id, "accept").body<OrderDto>().pickupToken)
        assertEquals(HttpStatusCode.OK, f.act(f.owner, b.id, "reject").status)
        assertEquals(2, f.act(f.owner, c3.id, "accept").body<OrderDto>().pickupToken)
        val rejected = f.c.send<Unit>(HttpMethod.Get, "/orders/${b.id}", f.customer).body<OrderDto>()
        assertEquals(OrderStatus.REJECTED, rejected.status)
        assertNull(rejected.pickupToken)
    }

    @Test
    fun customerCanCancelOnlyOwnPlacedOrders() = testApplication {
        val f = fixture()
        val a = f.place(); val b = f.place()
        assertEquals(HttpStatusCode.NotFound, f.act(f.owner, a.id, "cancel").status.let { if (it == HttpStatusCode.Forbidden) HttpStatusCode.NotFound else it })
        assertEquals(OrderStatus.CANCELLED, f.act(f.customer, a.id, "cancel").body<OrderDto>().status)
        assertEquals(HttpStatusCode.Conflict, f.act(f.customer, a.id, "cancel").status)
        assertEquals(HttpStatusCode.Conflict, f.act(f.owner, a.id, "accept").status)
        f.act(f.owner, b.id, "accept")
        assertEquals(HttpStatusCode.Conflict, f.act(f.customer, b.id, "cancel").status)
    }

    @Test
    fun invalidTransitionsAreRejected() = testApplication {
        val f = fixture()
        val o = f.place()
        // Cannot skip steps from PLACED.
        assertEquals(HttpStatusCode.Conflict, f.status(f.owner, o.id, OrderStatus.PREPARING).status)
        assertEquals(HttpStatusCode.Conflict, f.status(f.owner, o.id, OrderStatus.COMPLETED).status)
        // Accept/reject must use the dedicated endpoints.
        assertEquals(HttpStatusCode.BadRequest, f.status(f.owner, o.id, OrderStatus.ACCEPTED).status)
        f.act(f.owner, o.id, "accept")
        assertEquals(HttpStatusCode.Conflict, f.act(f.owner, o.id, "accept").status)
        assertEquals(HttpStatusCode.Conflict, f.act(f.owner, o.id, "reject").status)
        assertEquals(HttpStatusCode.Conflict, f.status(f.owner, o.id, OrderStatus.READY_FOR_PICKUP).status)
    }

    @Test
    fun concurrentAcceptsYieldExactlyOneAcceptanceAndToken() = testApplication {
        val f = fixture()
        val o = f.place()
        val results = coroutineScope { (1..6).map { async { f.act(f.owner, o.id, "accept") } }.awaitAll() }
        assertEquals(1, results.count { it.status == HttpStatusCode.OK }, results.map { it.status }.toString())
        assertTrue(results.filter { it.status != HttpStatusCode.OK }.all { it.status == HttpStatusCode.Conflict })
        val c = config().database
        DriverManager.getConnection(c.url, c.user, c.password).use { conn ->
            val n = conn.createStatement().executeQuery("SELECT COUNT(*) FROM pickup_tokens WHERE order_id=${o.id}").use { it.next(); it.getInt(1) }
            assertEquals(1, n)
        }
    }

    @Test
    fun concurrentAcceptsOfDifferentOrdersGetDistinctTokens() = testApplication {
        val f = fixture()
        val orders = (1..8).map { f.place() }
        val results = coroutineScope { orders.map { o -> async { f.act(f.owner, o.id, "accept") } }.awaitAll() }
        assertTrue(results.all { it.status == HttpStatusCode.OK }, results.map { it.status }.toString())
        val tokens = results.map { it.body<OrderDto>().pickupToken }
        assertEquals((1..8).toSet(), tokens.toSet())
    }

    @Test
    fun onlyTheHotelsOwnerCanManageOrders() = testApplication {
        val f = fixture()
        f.c.send(HttpMethod.Post, "/admin/owners", f.admin, CreateOwnerRequest("owner2", "owner2pass1", "Owner Two"))
        val owner2 = f.c.token("owner2", "owner2pass1")
        val o = f.place()
        assertEquals(HttpStatusCode.NotFound, f.act(owner2, o.id, "accept").status)
        assertEquals(HttpStatusCode.Forbidden, f.act(f.customer, o.id, "accept").status)
        assertEquals(HttpStatusCode.Forbidden, f.act(f.admin, o.id, "accept").status)
        assertEquals(HttpStatusCode.Forbidden, f.status(f.customer, o.id, OrderStatus.PREPARING).status)
        assertEquals(HttpStatusCode.NotFound, f.act(f.owner, 999999, "accept").status)
        assertEquals(OrderStatus.PLACED, f.c.send<Unit>(HttpMethod.Get, "/orders/${o.id}", f.customer).body<OrderDto>().status)
    }

    @Test
    fun ownerOrderListIsScopedAndFilterable() = testApplication {
        val f = fixture()
        f.c.send(HttpMethod.Post, "/admin/owners", f.admin, CreateOwnerRequest("owner2", "owner2pass1", "Owner Two"))
        val owner2 = f.c.token("owner2", "owner2pass1")
        val a = f.place(); f.place()
        f.act(f.owner, a.id, "accept")

        assertEquals(2, f.c.send<Unit>(HttpMethod.Get, "/owner/orders", f.owner).body<OrderPage>().total)
        assertEquals(1, f.c.send<Unit>(HttpMethod.Get, "/owner/orders?status=PLACED", f.owner).body<OrderPage>().total)
        assertEquals(2, f.c.send<Unit>(HttpMethod.Get, "/owner/orders?status=PLACED,ACCEPTED", f.owner).body<OrderPage>().total)
        assertEquals(0, f.c.send<Unit>(HttpMethod.Get, "/owner/orders", owner2).body<OrderPage>().total)
        assertEquals(HttpStatusCode.Forbidden, f.c.send<Unit>(HttpMethod.Get, "/owner/orders", f.customer).status)
        assertEquals(HttpStatusCode.BadRequest, f.c.send<Unit>(HttpMethod.Get, "/owner/orders?status=BAD", f.owner).status)
        assertEquals("Dev Customer", f.c.send<Unit>(HttpMethod.Get, "/owner/orders", f.owner).body<OrderPage>().items.first().customerName)
    }

    @Test
    fun dashboardSummarisesOrders() = testApplication {
        val f = fixture()
        val completed = f.place(2) // 30.00
        val rejected = f.place()
        val preparing = f.place()
        f.place() // stays placed
        f.act(f.owner, completed.id, "accept")
        for (s in listOf(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP, OrderStatus.COMPLETED)) f.status(f.owner, completed.id, s)
        f.act(f.owner, rejected.id, "reject")
        f.act(f.owner, preparing.id, "accept")
        f.status(f.owner, preparing.id, OrderStatus.PREPARING)

        val d = f.c.send<Unit>(HttpMethod.Get, "/owner/dashboard", f.owner).body<OwnerDashboard>()
        assertEquals("30.00", d.todaySales)
        assertEquals(1, d.placed)
        assertEquals(1, d.preparing)
        assertEquals(0, d.readyForPickup)
        assertEquals(1, d.completedToday)
        assertEquals(1, d.rejectedToday)
        assertEquals(HttpStatusCode.Forbidden, f.c.send<Unit>(HttpMethod.Get, "/owner/dashboard", f.customer).status)
    }
}
