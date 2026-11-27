package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.auth.*
import dev.livin.nammaoorufood.config.AppConfig
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.database.DatabaseFactory
import dev.livin.nammaoorufood.hotel.*
import dev.livin.nammaoorufood.notification.ExposedNotificationRepository
import dev.livin.nammaoorufood.order.*
import dev.livin.nammaoorufood.telegram.*
import dev.livin.nammaoorufood.user.ExposedUserRepository
import io.ktor.client.call.body
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class TelegramBotTest {
    private suspend inline fun <reified T> HttpClient.send(method: HttpMethod, path: String, token: String, body: T) = request(path) {
        this.method = method
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private class FakeTransport : TelegramTransport {
        val messages = mutableListOf<Pair<Long, String>>()
        val buttons = mutableListOf<String?>()
        var fail: Int? = null
        var incoming: List<TelegramUpdate> = emptyList()
        var lastOffset: Long? = null
        override fun updates(offset: Long?): List<TelegramUpdate> {
            lastOffset = offset
            return incoming
        }
        override fun send(chatId: Long, text: String, miniAppUrl: String?) {
            fail?.let { throw TelegramApiException(it, 1) }
            messages.add(chatId to text)
            buttons.add(miniAppUrl)
        }
    }

    @Test
    fun chatCancellationAndOrderNotifications() = testApplication {
        val config = AppConfig.fromEnv(System.getenv() + mapOf(
            "APP_ENV" to "test",
            "DB_URL" to testDatabaseUrl(),
            "LOGIN_RATE_LIMIT_PER_MINUTE" to "1000",
            "TELEGRAM_BOT_POLLING_ENABLED" to "false",
            "TELEGRAM_BOT_TOKEN" to "123:test-token",
        ))
        application { module(config, testImageKit()) }
        val http = createClient { install(ContentNegotiation) { json() } }
        http.get("/health")
        val source = DatabaseFactory.createDataSource(config.database)
        try {
            val db = Database(source)
            val users = ExposedUserRepository()
            val auth = AuthService(db, users, ExposedSessionRepository(), PasswordHasher(), TokenService(config.auth))
            val orders = OrderService(db, ExposedOrderRepository(), ExposedHotelRepository(), ExposedNotificationRepository())
            val fake = FakeTransport()
            val miniAppUrl = "https://miniapp.example.com"
            val bot = TelegramBot(db, auth, orders, fake, LoggerFactory.getLogger("TelegramBotTest"), miniAppUrl)
            val telegramId = System.currentTimeMillis()
            val chatId = telegramId
            db.transaction { conn -> conn.createStatement().use { it.executeUpdate("UPDATE telegram_bot_state SET next_offset = NULL, last_update_at = NULL WHERE id = 1") } }
            fun update(id: Long, userId: Long, text: String) = TelegramUpdate(
                id, TelegramMessage(TelegramSender(userId, "Ravi"), TelegramChat(userId, "private"), text),
            )
            val fields = sortedMapOf("auth_date" to Instant.now().epochSecond.toString(),
                "user" to """{"id":$telegramId,"first_name":"Ravi"}""")
            fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(value)
            }
            val secret = hmac("WebAppData".toByteArray(), "123:test-token".toByteArray())
            val signature = hmac(secret, fields.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val initData = fields.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}"
            } + "&hash=$signature"
            val loginResponse = http.post("/auth/telegram") {
                contentType(ContentType.Application.Json)
                setBody(TelegramLoginRequest(initData))
            }
            assertEquals(HttpStatusCode.OK, loginResponse.status)
            val telegramAccount = loginResponse.body<AuthResponse>()
            val customer = telegramAccount.accessToken
            suspend fun login(username: String, password: String): String = http.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(username, password))
            }.body<AuthResponse>().accessToken
            val owner = login("owner", "owner12345")
            val admin = login("admin", "admin12345")
            val hotel = http.send(HttpMethod.Post, "/owner/hotels", owner, HotelRequest("Telegram Test", null, "Street")).body<HotelDto>()
            http.send(HttpMethod.Patch, "/admin/hotels/${hotel.id}/approval", admin, ApprovalRequest(ApprovalStatus.APPROVED))
            http.send(HttpMethod.Put, "/owner/hotels/${hotel.id}/hours", owner, OperatingHoursRequest((1..7).map { OperatingHourDto(it, "00:00", "23:59") }))
            http.send(HttpMethod.Patch, "/owner/hotels/${hotel.id}/accepting-orders", owner, AcceptingOrdersRequest(true))
            val item = http.send(HttpMethod.Post, "/hotels/${hotel.id}/menu/items", owner, FoodItemRequest("Dosa", null, "25")).body<FoodItemDto>()
            suspend fun place(): OrderDto = http.send(HttpMethod.Post, "/orders", customer, PlaceOrderRequest(hotel.id, listOf(OrderLineRequest(item.id, 1)))).body()
            val first = place()
            assertFailsWith<IllegalStateException> {
                db.transaction { conn ->
                    orders.cancelCustomerOrder(conn, telegramAccount.user.id, first.id)
                    throw IllegalStateException("rollback test")
                }
            }
            assertEquals(OrderStatus.PLACED, http.get("/orders/${first.id}") { bearerAuth(customer) }.body<OrderDto>().status)
            val cancelledBeforeCommit = db.transaction { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM telegram_outbox WHERE order_id = ? AND order_status = 'CANCELLED'").use {
                    it.setLong(1, first.id)
                    it.executeQuery().use { rs -> check(rs.next()); rs.getInt(1) }
                }
            }
            assertEquals(0, cancelledBeforeCommit)
            assertFalse(bot.deliverOnce(), "No messages should be sent before the customer starts the bot")
            fake.incoming = listOf(update(telegramId, telegramId, "/start"))
            bot.pollOnce()
            fake.incoming = emptyList()
            bot.pollOnce()
            assertEquals(telegramId + 1, fake.lastOffset)
            bot.process(update(telegramId, telegramId, "/start"))
            bot.process(update(telegramId + 1, telegramId + 1, "/cancel ${first.id}"))
            assertEquals(OrderStatus.PLACED, http.get("/orders/${first.id}") { bearerAuth(customer) }.body<OrderDto>().status)
            bot.process(update(telegramId + 2, telegramId, "/cancel ${first.id}"))
            bot.process(update(telegramId + 2, telegramId, "/cancel ${first.id}"))
            assertEquals(OrderStatus.CANCELLED, http.get("/orders/${first.id}") { bearerAuth(customer) }.body<OrderDto>().status)

            val second = place()
            assertEquals(HttpStatusCode.OK, http.post("/orders/${second.id}/accept") { bearerAuth(owner) }.status)
            bot.process(update(telegramId + 3, telegramId, "/cancel ${second.id}"))
            for (status in listOf(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP, OrderStatus.COMPLETED)) {
                assertEquals(HttpStatusCode.OK, http.send(HttpMethod.Patch, "/orders/${second.id}/status", owner, StatusUpdateRequest(status)).status)
            }
            val rejected = place()
            assertEquals(HttpStatusCode.OK, http.post("/orders/${rejected.id}/reject") { bearerAuth(owner) }.status)

            fake.fail = 429
            assertTrue(bot.deliverOnce())
            val attempts = db.transaction { conn ->
                conn.prepareStatement("SELECT attempts FROM telegram_outbox WHERE user_id = ? ORDER BY id LIMIT 1").use {
                    it.setLong(1, telegramAccount.user.id)
                    it.executeQuery().use { rs -> check(rs.next()); rs.getInt(1) }
                }
            }
            assertEquals(1, attempts)
            db.transaction { conn ->
                conn.prepareStatement("UPDATE telegram_outbox SET next_attempt_at = UTC_TIMESTAMP(3) WHERE user_id = ?").use {
                    it.setLong(1, telegramAccount.user.id)
                    it.executeUpdate()
                }
            }
            fake.fail = null
            var count = 0
            while (count++ < 100 && bot.deliverOnce()) { }
            val messages = fake.messages.filter { it.first == chatId }.map { it.second }
            assertTrue(messages.any { it.contains("placed") && it.contains("#${first.id}") })
            assertTrue(messages.any { it.contains("cancelled") && it.contains("#${first.id}") })
            assertTrue(messages.any { it.contains("accepted") && it.contains("Pickup token") })
            assertTrue(messages.any { it.contains("no longer be cancelled") })
            assertTrue(messages.any { it.contains("being prepared") })
            assertTrue(messages.any { it.contains("ready for pickup") })
            assertTrue(messages.any { it.contains("completed") })
            assertTrue(messages.any { it.contains("rejected") })
            assertTrue(fake.messages.any { it.first == telegramId + 1 && it.second == "Order not found." })
            assertEquals(1, messages.count { it.startsWith("Welcome") })
            assertEquals(1, fake.buttons.count { it == miniAppUrl })

            bot.process(update(telegramId + 4, telegramId, "/orders"))
            fake.fail = 403
            assertTrue(bot.deliverOnce())
            val chatAfterBlock = db.transaction { conn ->
                conn.prepareStatement("SELECT private_chat_id FROM telegram_identities WHERE telegram_user_id = ?").use {
                    it.setLong(1, telegramId)
                    it.executeQuery().use { rs -> check(rs.next()); rs.getLong(1); rs.wasNull() }
                }
            }
            assertTrue(chatAfterBlock)
            fake.fail = null
            bot.process(update(telegramId + 5, telegramId, "/start"))
            assertTrue(bot.deliverOnce())
            assertEquals(2, fake.messages.count { it.first == chatId && it.second.startsWith("Welcome") })
        } finally {
            source.close()
        }
    }
}
