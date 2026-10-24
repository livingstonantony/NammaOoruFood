package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.auth.AuthResponse
import dev.livin.nammaoorufood.auth.LoginRequest
import dev.livin.nammaoorufood.config.AppConfig
import dev.livin.nammaoorufood.hotel.*
import dev.livin.nammaoorufood.notification.*
import dev.livin.nammaoorufood.order.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import java.sql.DriverManager
import kotlin.test.*

class NotificationTest {
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

    private suspend fun Fx.place() =
        c.send(HttpMethod.Post, "/orders", customer, PlaceOrderRequest(hotel.id, listOf(OrderLineRequest(item.id, 1)))).body<OrderDto>()

    @Test
    fun readyNotificationIsCreatedAndCanBeMarkedRead() = testApplication {
        val f = fixture()
        val o = f.place()
        assertEquals(0, f.c.send<Unit>(HttpMethod.Get, "/notifications", f.customer).body<NotificationPage>().items.size)

        f.c.send<Unit>(HttpMethod.Post, "/orders/${o.id}/accept", f.owner)
        for (s in listOf(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP)) f.c.send(HttpMethod.Patch, "/orders/${o.id}/status", f.owner, StatusUpdateRequest(s))

        val page = f.c.send<Unit>(HttpMethod.Get, "/notifications", f.customer).body<NotificationPage>()
        assertEquals(setOf(NotificationType.ORDER_ACCEPTED, NotificationType.ORDER_PREPARING, NotificationType.ORDER_READY), page.items.map { it.type }.toSet())
        assertEquals(3, page.unreadCount)
        val ready = page.items.first { it.type == NotificationType.ORDER_READY }
        assertTrue(ready.message.contains("token 1"))
        assertEquals(o.id, ready.orderId)

        assertEquals(HttpStatusCode.NoContent, f.c.send<Unit>(HttpMethod.Post, "/notifications/${ready.id}/read", f.customer).status)
        assertEquals(2, f.c.send<Unit>(HttpMethod.Get, "/notifications?unread=true", f.customer).body<NotificationPage>().unreadCount)
        assertEquals(HttpStatusCode.NoContent, f.c.send<Unit>(HttpMethod.Post, "/notifications/read-all", f.customer).status)
        assertEquals(0, f.c.send<Unit>(HttpMethod.Get, "/notifications?unread=true", f.customer).body<NotificationPage>().items.size)
    }

    @Test
    fun rejectionNotifiesCustomerAndNoDuplicatesOnRepeatedAction() = testApplication {
        val f = fixture()
        val o = f.place()
        f.c.send<Unit>(HttpMethod.Post, "/orders/${o.id}/reject", f.owner)
        assertEquals(HttpStatusCode.Conflict, f.c.send<Unit>(HttpMethod.Post, "/orders/${o.id}/reject", f.owner).status)
        val items = f.c.send<Unit>(HttpMethod.Get, "/notifications", f.customer).body<NotificationPage>().items
        assertEquals(listOf(NotificationType.ORDER_REJECTED), items.map { it.type })
    }

    @Test
    fun notificationsArePrivateToTheirRecipient() = testApplication {
        val f = fixture()
        val o = f.place()
        f.c.send<Unit>(HttpMethod.Post, "/orders/${o.id}/accept", f.owner)
        val id = f.c.send<Unit>(HttpMethod.Get, "/notifications", f.customer).body<NotificationPage>().items.single().id
        assertEquals(0, f.c.send<Unit>(HttpMethod.Get, "/notifications", f.owner).body<NotificationPage>().items.size)
        assertEquals(HttpStatusCode.NotFound, f.c.send<Unit>(HttpMethod.Post, "/notifications/$id/read", f.owner).status)
        assertEquals(HttpStatusCode.Unauthorized, f.c.get("/notifications").status)
    }

    @Test
    fun activeOrdersExcludeTerminalOnesAndTrackStatus() = testApplication {
        val f = fixture()
        val a = f.place(); val b = f.place(); f.place()
        f.c.send<Unit>(HttpMethod.Post, "/orders/${a.id}/reject", f.owner)
        f.c.send<Unit>(HttpMethod.Post, "/orders/${b.id}/accept", f.owner)
        val active = f.c.send<Unit>(HttpMethod.Get, "/orders/active", f.customer).body<List<OrderDto>>()
        assertEquals(2, active.size)
        assertEquals(OrderStatus.ACCEPTED, active.first { it.id == b.id }.status)
        assertEquals(HttpStatusCode.Forbidden, f.c.send<Unit>(HttpMethod.Get, "/orders/active", f.owner).status)
    }
}
