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

class OrderApiTest {
    private fun config() = AppConfig.fromEnv(
        mapOf(
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

    private class Fixture(val c: HttpClient, val owner: String, val admin: String, val customer: String, val hotel: HotelDto, val parotta: FoodItemDto, val rice: FoodItemDto)

    private suspend inline fun <reified T> HttpClient.send(method: HttpMethod, path: String, token: String, body: T? = null, key: String? = null) =
        request(path) {
            this.method = method
            bearerAuth(token)
            if (key != null) header("Idempotency-Key", key)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private suspend fun HttpClient.token(user: String, pw: String) =
        post("/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(user, pw)) }.body<AuthResponse>().accessToken

    private suspend fun ApplicationTestBuilder.fixture(open: Boolean = true): Fixture {
        application { module(config()) }
        val c = createClient { install(ContentNegotiation) { json() } }
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val customer = c.token("customer", "customer12345")
        val hotel = c.send(HttpMethod.Post, "/owner/hotels", owner, HotelRequest("Amma Hotel", null, "Main St")).body<HotelDto>()
        c.send(HttpMethod.Patch, "/admin/hotels/${hotel.id}/approval", admin, ApprovalRequest(ApprovalStatus.APPROVED))
        if (open) {
            c.send(HttpMethod.Put, "/owner/hotels/${hotel.id}/hours", owner, OperatingHoursRequest((1..7).map { OperatingHourDto(it, "00:00", "23:59") }))
            c.send(HttpMethod.Patch, "/owner/hotels/${hotel.id}/accepting-orders", owner, AcceptingOrdersRequest(true))
        }
        val parotta = c.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/items", owner, FoodItemRequest("Parotta", null, "15.00")).body<FoodItemDto>()
        val rice = c.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/items", owner, FoodItemRequest("Fried Rice", null, "90.00")).body<FoodItemDto>()
        return Fixture(c, owner, admin, customer, hotel, parotta, rice)
    }

    @Test
    fun serverComputesTotalAndSnapshotsPrices() = testApplication {
        val f = fixture()
        val req = PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 2), OrderLineRequest(f.rice.id, 1), OrderLineRequest(f.parotta.id, 1)), "less spicy")
        val resp = f.c.send(HttpMethod.Post, "/orders", f.customer, req)
        assertEquals(HttpStatusCode.Created, resp.status)
        val order = resp.body<OrderDto>()
        assertEquals(OrderStatus.PLACED, order.status)
        assertEquals("135.00", order.total)
        assertNull(order.pickupToken)
        assertEquals(2, order.items.size)
        assertEquals(3, order.items.first { it.name == "Parotta" }.quantity)

        // Later menu edits must not change the historical bill.
        f.c.send(HttpMethod.Put, "/hotels/${f.hotel.id}/menu/items/${f.parotta.id}", f.owner, FoodItemRequest("Parotta Special", null, "99.00"))
        val again = f.c.send<Unit>(HttpMethod.Get, "/orders/${order.id}", f.customer).body<OrderDto>()
        assertEquals("135.00", again.total)
        assertEquals("Parotta", again.items.first { it.quantity == 3 }.name)
        assertEquals("15.00", again.items.first { it.quantity == 3 }.unitPrice)
    }

    @Test
    fun rejectsClosedHotelUnavailableItemsAndForeignItems() = testApplication {
        val f = fixture()
        f.c.send(HttpMethod.Patch, "/hotels/${f.hotel.id}/menu/items/${f.rice.id}/availability", f.owner, AvailabilityRequest(false))
        val unavailable = f.c.send(HttpMethod.Post, "/orders", f.customer, PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.rice.id, 1))))
        assertEquals(HttpStatusCode.Conflict, unavailable.status)

        // Item from another hotel is rejected (one hotel per order).
        val other = f.c.send(HttpMethod.Post, "/owner/hotels", f.owner, HotelRequest("Second", null, "St")).body<HotelDto>()
        f.c.send(HttpMethod.Patch, "/admin/hotels/${other.id}/approval", f.admin, ApprovalRequest(ApprovalStatus.APPROVED))
        val otherItem = f.c.send(HttpMethod.Post, "/hotels/${other.id}/menu/items", f.owner, FoodItemRequest("Dosa", null, "30")).body<FoodItemDto>()
        val mixed = f.c.send(HttpMethod.Post, "/orders", f.customer, PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 1), OrderLineRequest(otherItem.id, 1))))
        assertEquals(HttpStatusCode.BadRequest, mixed.status)

        f.c.send(HttpMethod.Patch, "/owner/hotels/${f.hotel.id}/accepting-orders", f.owner, AcceptingOrdersRequest(false))
        val closed = f.c.send(HttpMethod.Post, "/orders", f.customer, PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 1))))
        assertEquals(HttpStatusCode.Conflict, closed.status)
    }

    @Test
    fun hotelOutsideOperatingHoursCannotTakeOrders() = testApplication {
        val f = fixture(open = false)
        val r = f.c.send(HttpMethod.Post, "/orders", f.customer, PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 1))))
        assertEquals(HttpStatusCode.Conflict, r.status)
    }

    @Test
    fun validatesQuantitiesAndItems() = testApplication {
        val f = fixture()
        for (items in listOf(emptyList(), listOf(OrderLineRequest(f.parotta.id, 0)), listOf(OrderLineRequest(f.parotta.id, -1)), listOf(OrderLineRequest(f.parotta.id, 21)), listOf(OrderLineRequest(999999, 1)))) {
            val r = f.c.send(HttpMethod.Post, "/orders", f.customer, PlaceOrderRequest(f.hotel.id, items))
            assertEquals(HttpStatusCode.BadRequest, r.status, "items=$items")
        }
        assertEquals(HttpStatusCode.NotFound, f.c.send(HttpMethod.Post, "/orders", f.customer, PlaceOrderRequest(424242, listOf(OrderLineRequest(f.parotta.id, 1)))).status)
    }

    @Test
    fun onlyCustomersCanPlaceOrders() = testApplication {
        val f = fixture()
        val req = PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 1)))
        assertEquals(HttpStatusCode.Forbidden, f.c.send(HttpMethod.Post, "/orders", f.owner, req).status)
        assertEquals(HttpStatusCode.Forbidden, f.c.send(HttpMethod.Post, "/orders", f.admin, req).status)
        assertEquals(HttpStatusCode.Unauthorized, f.c.post("/orders").status)
    }

    @Test
    fun idempotencyKeyPreventsDuplicateOrders() = testApplication {
        val f = fixture()
        val req = PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 1)))
        val first = f.c.send(HttpMethod.Post, "/orders", f.customer, req, key = "key-12345678")
        val second = f.c.send(HttpMethod.Post, "/orders", f.customer, req, key = "key-12345678")
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(first.body<OrderDto>().id, second.body<OrderDto>().id)
        assertEquals(1, f.c.send<Unit>(HttpMethod.Get, "/orders", f.customer).body<OrderPage>().total)
        assertEquals(HttpStatusCode.BadRequest, f.c.send(HttpMethod.Post, "/orders", f.customer, req, key = "bad key!").status)
    }

    @Test
    fun concurrentRetriesWithSameKeyCreateOneOrder() = testApplication {
        val f = fixture()
        val req = PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 1)))
        val results = coroutineScope {
            (1..5).map { async { f.c.send(HttpMethod.Post, "/orders", f.customer, req, key = "race-key-12345") } }.awaitAll()
        }
        assertTrue(results.all { it.status == HttpStatusCode.Created || it.status == HttpStatusCode.OK })
        assertEquals(1, f.c.send<Unit>(HttpMethod.Get, "/orders", f.customer).body<OrderPage>().total)
    }

    @Test
    fun customersSeeOnlyTheirOwnOrders() = testApplication {
        val f = fixture()
        val other = f.c.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(dev.livin.nammaoorufood.auth.RegisterRequest("second", "secondpass1", "Second"))
        }.body<AuthResponse>().accessToken
        val req = PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 1)))
        val mine = f.c.send(HttpMethod.Post, "/orders", f.customer, req).body<OrderDto>()

        assertEquals(HttpStatusCode.NotFound, f.c.send<Unit>(HttpMethod.Get, "/orders/${mine.id}", other).status)
        assertEquals(0, f.c.send<Unit>(HttpMethod.Get, "/orders", other).body<OrderPage>().total)
        assertEquals(HttpStatusCode.OK, f.c.send<Unit>(HttpMethod.Get, "/orders/${mine.id}", f.customer).status)
        // The hotel's owner and admins may view it.
        assertEquals(HttpStatusCode.OK, f.c.send<Unit>(HttpMethod.Get, "/orders/${mine.id}", f.owner).status)
        assertEquals(HttpStatusCode.OK, f.c.send<Unit>(HttpMethod.Get, "/orders/${mine.id}", f.admin).status)
    }

    @Test
    fun otherHotelOwnerCannotViewOrder() = testApplication {
        val f = fixture()
        f.c.send(HttpMethod.Post, "/admin/owners", f.admin, CreateOwnerRequest("owner2", "owner2pass1", "Owner Two"))
        val owner2 = f.c.token("owner2", "owner2pass1")
        val order = f.c.send(HttpMethod.Post, "/orders", f.customer, PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 1)))).body<OrderDto>()
        assertEquals(HttpStatusCode.NotFound, f.c.send<Unit>(HttpMethod.Get, "/orders/${order.id}", owner2).status)
    }

    @Test
    fun historyIsPaginatedAndFilterable() = testApplication {
        val f = fixture()
        repeat(3) { f.c.send(HttpMethod.Post, "/orders", f.customer, PlaceOrderRequest(f.hotel.id, listOf(OrderLineRequest(f.parotta.id, 1)))) }
        val page = f.c.send<Unit>(HttpMethod.Get, "/orders?page=2&pageSize=2", f.customer).body<OrderPage>()
        assertEquals(3, page.total)
        assertEquals(1, page.items.size)
        assertEquals(0, f.c.send<Unit>(HttpMethod.Get, "/orders?status=COMPLETED", f.customer).body<OrderPage>().total)
        assertEquals(HttpStatusCode.BadRequest, f.c.send<Unit>(HttpMethod.Get, "/orders?status=NOPE", f.customer).status)
    }
}
