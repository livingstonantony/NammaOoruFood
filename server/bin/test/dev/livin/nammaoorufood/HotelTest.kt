package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.auth.AuthResponse
import dev.livin.nammaoorufood.auth.LoginRequest
import dev.livin.nammaoorufood.config.AppConfig
import dev.livin.nammaoorufood.hotel.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import java.sql.DriverManager
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*

class OperatingHoursTest {
    private fun at(day: Int, time: String) =
        ZonedDateTime.of(2026, 10, 18 + day, LocalTime.parse(time).hour, LocalTime.parse(time).minute, 0, 0, ZoneId.of("Asia/Kolkata"))

    @Test
    fun sameDayWindow() {
        val h = listOf(OperatingHour(1, LocalTime.of(17, 0), LocalTime.of(22, 0)))
        assertTrue(OperatingHours.isOpenAt(h, at(1, "17:00")))
        assertTrue(OperatingHours.isOpenAt(h, at(1, "21:59")))
        assertFalse(OperatingHours.isOpenAt(h, at(1, "22:00")))
        assertFalse(OperatingHours.isOpenAt(h, at(1, "16:59")))
        assertFalse(OperatingHours.isOpenAt(h, at(2, "18:00")))
    }

    @Test
    fun overnightWindowSpillsIntoNextDay() {
        val h = listOf(OperatingHour(7, LocalTime.of(18, 0), LocalTime.of(2, 0))) // Sunday
        assertTrue(OperatingHours.isOpenAt(h, at(7, "23:30")))
        assertTrue(OperatingHours.isOpenAt(h, at(8, "01:59"))) // Monday 01:59 (day 19+8 rolls over)
        assertFalse(OperatingHours.isOpenAt(h, at(8, "02:00")))
        assertFalse(OperatingHours.isOpenAt(h, at(7, "10:00")))
    }
}

class HotelApiTest {
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
                listOf("notifications", "auth_sessions", "hotel_operating_hours", "menu_categories", "pickup_tokens", "order_items", "orders", "food_items", "hotels", "users")
                    .forEach { st.execute("TRUNCATE TABLE $it") }
                st.execute("SET FOREIGN_KEY_CHECKS = 1")
            }
        }
    }

    private fun ApplicationTestBuilder.api(): HttpClient {
        application { module(config()) }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private suspend fun HttpClient.token(user: String, pw: String) =
        post("/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(user, pw)) }.body<AuthResponse>().accessToken

    private suspend inline fun <reified T> HttpClient.send(method: HttpMethod, path: String, token: String, body: T? = null) =
        request(path) {
            this.method = method
            bearerAuth(token)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private suspend fun HttpClient.createApprovedHotel(owner: String, admin: String): HotelDto {
        val hotel = send(HttpMethod.Post, "/owner/hotels", owner, HotelRequest("Amma Hotel", "Parotta", "Main St", "9876543210")).body<HotelDto>()
        send(HttpMethod.Patch, "/admin/hotels/${hotel.id}/approval", admin, ApprovalRequest(ApprovalStatus.APPROVED))
        return hotel
    }

    @Test
    fun ownerCreatesHotelAndItIsHiddenUntilApproved() = testApplication {
        val c = api()
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val customer = c.token("customer", "customer12345")

        val hotel = c.send(HttpMethod.Post, "/owner/hotels", owner, HotelRequest("Amma Hotel", null, "Main St")).body<HotelDto>()
        assertEquals(ApprovalStatus.PENDING, hotel.approvalStatus)
        assertFalse(hotel.isOpen)

        assertEquals(0, c.send<Unit>(HttpMethod.Get, "/hotels", customer).body<HotelPage>().total)
        assertEquals(HttpStatusCode.NotFound, c.send<Unit>(HttpMethod.Get, "/hotels/${hotel.id}", customer).status)
        assertEquals(HttpStatusCode.OK, c.send<Unit>(HttpMethod.Get, "/hotels/${hotel.id}", owner).status)

        // Cannot start accepting orders before approval.
        assertEquals(HttpStatusCode.Conflict, c.send(HttpMethod.Patch, "/owner/hotels/${hotel.id}/accepting-orders", owner, AcceptingOrdersRequest(true)).status)

        c.send(HttpMethod.Patch, "/admin/hotels/${hotel.id}/approval", admin, ApprovalRequest(ApprovalStatus.APPROVED))
        assertEquals(1, c.send<Unit>(HttpMethod.Get, "/hotels", customer).body<HotelPage>().total)
        assertEquals(HttpStatusCode.OK, c.send(HttpMethod.Patch, "/owner/hotels/${hotel.id}/accepting-orders", owner, AcceptingOrdersRequest(true)).status)
    }

    @Test
    fun hotelIsOpenOnlyWithinHoursAndAcceptingOrders() = testApplication {
        val c = api()
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val hotel = c.createApprovedHotel(owner, admin)
        c.send(HttpMethod.Patch, "/owner/hotels/${hotel.id}/accepting-orders", owner, AcceptingOrdersRequest(true))

        val allDay = (1..7).map { OperatingHourDto(it, "00:00", "23:59") }
        val resp = c.send(HttpMethod.Put, "/owner/hotels/${hotel.id}/hours", owner, OperatingHoursRequest(allDay))
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.body<HotelDto>().isOpen || ZonedDateTime.now(BUSINESS_ZONE).toLocalTime() >= LocalTime.of(23, 59))

        c.send(HttpMethod.Put, "/owner/hotels/${hotel.id}/hours", owner, OperatingHoursRequest(emptyList()))
        assertFalse(c.send<Unit>(HttpMethod.Get, "/hotels/${hotel.id}", owner).body<HotelDto>().isOpen)
    }

    @Test
    fun invalidOperatingHoursAreRejected() = testApplication {
        val c = api()
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val hotel = c.createApprovedHotel(owner, admin)
        val dup = OperatingHoursRequest(listOf(OperatingHourDto(1, "10:00", "12:00"), OperatingHourDto(1, "13:00", "15:00")))
        assertEquals(HttpStatusCode.BadRequest, c.send(HttpMethod.Put, "/owner/hotels/${hotel.id}/hours", owner, dup).status)
        val bad = OperatingHoursRequest(listOf(OperatingHourDto(9, "10:00", "12:00")))
        assertEquals(HttpStatusCode.BadRequest, c.send(HttpMethod.Put, "/owner/hotels/${hotel.id}/hours", owner, bad).status)
    }

    @Test
    fun menuManagementAndCustomerVisibility() = testApplication {
        val c = api()
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val customer = c.token("customer", "customer12345")
        val hotel = c.createApprovedHotel(owner, admin)

        val cat = c.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/categories", owner, CategoryRequest("Mains")).body<CategoryDto>()
        val parotta = c.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/items", owner, FoodItemRequest("Parotta", null, "15.00", cat.id)).body<FoodItemDto>()
        val rice = c.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/items", owner, FoodItemRequest("Fried Rice", null, "90", cat.id)).body<FoodItemDto>()
        assertEquals("90.00", rice.price)

        c.send(HttpMethod.Patch, "/hotels/${hotel.id}/menu/items/${rice.id}/availability", owner, AvailabilityRequest(false))
        val custMenu = c.send<Unit>(HttpMethod.Get, "/hotels/${hotel.id}/menu", customer).body<MenuResponse>()
        assertEquals(listOf("Parotta"), custMenu.items.map { it.name })
        val ownerMenu = c.send<Unit>(HttpMethod.Get, "/hotels/${hotel.id}/menu", owner).body<MenuResponse>()
        assertEquals(2, ownerMenu.items.size)

        val updated = c.send(HttpMethod.Put, "/hotels/${hotel.id}/menu/items/${parotta.id}", owner, FoodItemRequest("Parotta", "Hot", "18.50", cat.id)).body<FoodItemDto>()
        assertEquals("18.50", updated.price)
        assertEquals(HttpStatusCode.NoContent, c.send<Unit>(HttpMethod.Delete, "/hotels/${hotel.id}/menu/items/${rice.id}", owner).status)
    }

    @Test
    fun priceValidation() = testApplication {
        val c = api()
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val hotel = c.createApprovedHotel(owner, admin)
        for (bad in listOf("-5", "0", "abc", "10.999", "1e9")) {
            val r = c.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/items", owner, FoodItemRequest("X", null, bad))
            assertEquals(HttpStatusCode.BadRequest, r.status, "price=$bad")
        }
    }

    @Test
    fun otherOwnersAndCustomersCannotModifyHotel() = testApplication {
        val c = api()
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val customer = c.token("customer", "customer12345")
        val hotel = c.createApprovedHotel(owner, admin)

        val created = c.send(HttpMethod.Post, "/admin/owners", admin, CreateOwnerRequest("owner2", "owner2pass1", "Second Owner"))
        assertEquals(HttpStatusCode.Created, created.status)
        val owner2 = c.token("owner2", "owner2pass1")

        val item = FoodItemRequest("Hack", null, "10")
        assertEquals(HttpStatusCode.Forbidden, c.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/items", owner2, item).status)
        assertEquals(HttpStatusCode.Forbidden, c.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/items", customer, item).status)
        assertEquals(HttpStatusCode.Forbidden, c.send(HttpMethod.Put, "/owner/hotels/${hotel.id}", owner2, HotelRequest("Stolen", null, "x")).status)
        assertEquals(HttpStatusCode.Forbidden, c.send(HttpMethod.Put, "/owner/hotels/${hotel.id}/hours", owner2, OperatingHoursRequest(emptyList())).status)
        assertEquals(HttpStatusCode.Forbidden, c.send(HttpMethod.Patch, "/admin/hotels/${hotel.id}/approval", owner, ApprovalRequest(ApprovalStatus.SUSPENDED)).status)
        assertEquals(HttpStatusCode.Forbidden, c.send(HttpMethod.Post, "/admin/owners", customer, CreateOwnerRequest("owner3", "owner3pass1", "X")).status)
        assertEquals(HttpStatusCode.Forbidden, c.send(HttpMethod.Post, "/owner/hotels", customer, HotelRequest("H", null, "a")).status)
        assertEquals(HttpStatusCode.Unauthorized, c.get("/hotels").status)
    }

    @Test
    fun suspendingHotelHidesItAndStopsOrders() = testApplication {
        val c = api()
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val customer = c.token("customer", "customer12345")
        val hotel = c.createApprovedHotel(owner, admin)
        c.send(HttpMethod.Patch, "/owner/hotels/${hotel.id}/accepting-orders", owner, AcceptingOrdersRequest(true))

        c.send(HttpMethod.Patch, "/admin/hotels/${hotel.id}/approval", admin, ApprovalRequest(ApprovalStatus.SUSPENDED))
        assertEquals(0, c.send<Unit>(HttpMethod.Get, "/hotels", customer).body<HotelPage>().total)
        val seen = c.send<Unit>(HttpMethod.Get, "/hotels/${hotel.id}", owner).body<HotelDto>()
        assertFalse(seen.acceptingOrders)
        assertFalse(seen.isOpen)
    }

    @Test
    fun searchAndPagination() = testApplication {
        val c = api()
        val owner = c.token("owner", "owner12345")
        val admin = c.token("admin", "admin12345")
        val customer = c.token("customer", "customer12345")
        for (n in listOf("Alpha Hotel", "Beta Mess", "Alpha Tiffin")) {
            val h = c.send(HttpMethod.Post, "/owner/hotels", owner, HotelRequest(n, null, "St")).body<HotelDto>()
            c.send(HttpMethod.Patch, "/admin/hotels/${h.id}/approval", admin, ApprovalRequest(ApprovalStatus.APPROVED))
        }
        assertEquals(2, c.send<Unit>(HttpMethod.Get, "/hotels?q=alpha", customer).body<HotelPage>().total)
        val page = c.send<Unit>(HttpMethod.Get, "/hotels?page=2&pageSize=2", customer).body<HotelPage>()
        assertEquals(3, page.total)
        assertEquals(1, page.items.size)
    }
}
