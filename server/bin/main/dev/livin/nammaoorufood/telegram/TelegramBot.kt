package dev.livin.nammaoorufood.telegram

import dev.livin.nammaoorufood.auth.AuthService
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.order.OrderService
import dev.livin.nammaoorufood.plugins.ConflictException
import dev.livin.nammaoorufood.plugins.NotFoundException
import dev.livin.nammaoorufood.plugins.UnauthorizedException
import dev.livin.nammaoorufood.websocket.ServerEventBus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Duration
import java.util.UUID

@Serializable
data class TelegramSender(val id: Long, val first_name: String, val last_name: String? = null, val username: String? = null, val is_bot: Boolean = false)

@Serializable
data class TelegramChat(val id: Long, val type: String)

@Serializable
data class TelegramMessage(val from: TelegramSender? = null, val chat: TelegramChat, val text: String? = null)

@Serializable
data class TelegramUpdate(val update_id: Long, val message: TelegramMessage? = null)

@Serializable
private data class TelegramParameters(val retry_after: Int? = null)

@Serializable
private data class UpdatesResponse(val ok: Boolean, val result: List<TelegramUpdate> = emptyList(), val error_code: Int? = null, val parameters: TelegramParameters? = null)

@Serializable
private data class SendResponse(val ok: Boolean, val error_code: Int? = null, val parameters: TelegramParameters? = null)

@Serializable
internal data class TelegramWebApp(val url: String)

@Serializable
internal data class TelegramInlineButton(val text: String, val web_app: TelegramWebApp)

@Serializable
internal data class TelegramInlineKeyboard(val inline_keyboard: List<List<TelegramInlineButton>>)

@Serializable
internal data class SendMessage(val chat_id: Long, val text: String, val reply_markup: TelegramInlineKeyboard? = null)

internal fun telegramMessage(chatId: Long, text: String, miniAppUrl: String?): SendMessage =
    SendMessage(chatId, text, miniAppUrl?.let {
        TelegramInlineKeyboard(listOf(listOf(TelegramInlineButton("Open Mini App", TelegramWebApp(it)))))
    })

class TelegramApiException(val code: Int, val retryAfter: Int? = null) : RuntimeException("Telegram API error $code")

interface TelegramTransport {
    fun updates(offset: Long?): List<TelegramUpdate>
    fun send(chatId: Long, text: String, miniAppUrl: String? = null)
}

class TelegramHttpTransport(token: String) : TelegramTransport {
    private val base = "https://api.telegram.org/bot$token/"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override fun updates(offset: Long?): List<TelegramUpdate> {
        val suffix = "getUpdates?timeout=25&allowed_updates=%5B%22message%22%5D" +
            (offset?.let { "&offset=$it" } ?: "")
        val body = request(suffix, null, 35)
        val response = try {
            json.decodeFromString<UpdatesResponse>(body)
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid Telegram API response")
        }
        if (!response.ok) throw TelegramApiException(response.error_code ?: 0, response.parameters?.retry_after)
        return response.result
    }

    override fun send(chatId: Long, text: String, miniAppUrl: String?) {
        val body = request("sendMessage", json.encodeToString(telegramMessage(chatId, text, miniAppUrl)), 15)
        val response = try {
            json.decodeFromString<SendResponse>(body)
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid Telegram API response")
        }
        if (!response.ok) throw TelegramApiException(response.error_code ?: 0, response.parameters?.retry_after)
    }

    private fun request(path: String, body: String?, timeoutSeconds: Long): String {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(timeoutSeconds))
        val request = if (body == null) builder.GET().build() else builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 500) throw IOException("Telegram API temporarily unavailable")
        return response.body()
    }
}

class TelegramBot(
    private val db: Database,
    private val auth: AuthService,
    private val orders: OrderService,
    private val transport: TelegramTransport,
    private val log: Logger,
    private val miniAppUrl: String? = null,
) {
    fun pollOnce() {
        val offset = db.transaction { conn ->
            conn.prepareStatement(
                "UPDATE telegram_bot_state SET next_offset = NULL, last_update_at = NULL WHERE id = 1 AND last_update_at < UTC_TIMESTAMP(3) - INTERVAL 6 DAY",
            ).use { it.executeUpdate() }
            conn.prepareStatement(
                "SELECT next_offset FROM telegram_bot_state WHERE id = 1",
            ).use { it.executeQuery().use { result -> check(result.next()); result.getLong(1).let { value -> if (result.wasNull()) null else value } } }
        }
        for (update in transport.updates(offset)) process(update)
    }

    fun process(update: TelegramUpdate) {
        var changedOrder: Pair<Long, Long>? = null
        db.transaction { conn ->
            val current = conn.prepareStatement("SELECT next_offset FROM telegram_bot_state WHERE id = 1 FOR UPDATE").use {
                it.executeQuery().use { result -> check(result.next()); result.getLong(1).let { value -> if (result.wasNull()) null else value } }
            }
            // A repeated update after a successful transaction must not repeat its action.
            if (current != null && update.update_id < current) return@transaction
            val message = update.message
            val sender = message?.from
            if (message != null && message.chat.type == "private" && sender != null && !sender.is_bot && sender.id > 0) {
                val name = listOfNotNull(sender.first_name, sender.last_name).joinToString(" ").trim().take(100)
                if (name.isNotEmpty()) {
                    val identity = VerifiedTelegramUser(sender.id, name, sender.username?.take(255))
                    val user = try {
                        auth.resolveTelegramCustomer(conn, identity)
                    } catch (_: UnauthorizedException) {
                        null
                    }
                    if (user != null) {
                        conn.prepareStatement("UPDATE telegram_identities SET private_chat_id = ? WHERE telegram_user_id = ?").use {
                            it.setLong(1, message.chat.id)
                            it.setLong(2, sender.id)
                            it.executeUpdate()
                        }
                        val text = message.text.orEmpty().trim()
                        val command = text.substringBefore(' ').substringBefore('@')
                        val response = when (command) {
                            "/start", "/help" -> if (miniAppUrl?.endsWith("?mock=true") == true) {
                                "Welcome to Namma Ooru Food. The Mini App is in mock mode: orders are simulated and will not appear in the bot or owner app."
                            } else {
                                "Welcome to Namma Ooru Food. Open the Mini App to order. Use /orders to view active orders and /cancel ORDER_ID to cancel a placed order."
                            }
                            "/orders" -> {
                                val active = orders.activeCustomerOrders(conn, user.id)
                                if (active.isEmpty()) "You have no active orders." else
                                    active.take(20).joinToString("\n") { "#${it.id} ${it.hotelName}: ${it.status}" } +
                                        if (active.size > 20) "\nMore orders are available in the Mini App." else ""
                            }
                            "/cancel" -> {
                                val id = text.substringAfter(' ', "").trim().toLongOrNull()
                                if (id == null || id <= 0) "Use /cancel ORDER_ID." else try {
                                    val result = orders.cancelCustomerOrder(conn, user.id, id)
                                    changedOrder = result.id to result.hotelId
                                    "Order #$id cancelled."
                                } catch (_: NotFoundException) {
                                    "Order not found."
                                } catch (_: ConflictException) {
                                    "Order #$id can no longer be cancelled."
                                }
                            }
                            else -> "Use /orders to view active orders, /cancel ORDER_ID to cancel a placed order, or open the Mini App to order."
                        }
                        TelegramOutbox.reply(conn, user.id, update.update_id, response, command == "/start" || command == "/help")
                    }
                }
            }
            conn.prepareStatement("UPDATE telegram_bot_state SET next_offset = ?, last_update_at = UTC_TIMESTAMP(3) WHERE id = 1").use {
                it.setLong(1, Math.addExact(update.update_id, 1))
                it.executeUpdate()
            }
        }
        changedOrder?.let { (orderId, hotelId) ->
            ServerEventBus.broadcast("ORDER_UPDATED", orderId)
            ServerEventBus.broadcast("MENU_UPDATED", hotelId)
        }
    }

    private data class Delivery(val id: Long, val userId: Long, val chatId: Long, val message: String, val attempts: Int, val lease: String, val openMiniApp: Boolean)

    fun deliverOnce(): Boolean {
        val delivery = db.transaction { conn -> claim(conn) } ?: return false
        try {
            transport.send(delivery.chatId, delivery.message, miniAppUrl.takeIf { delivery.openMiniApp })
            db.transaction { conn -> finish(conn, delivery, "sent_at = UTC_TIMESTAMP(3)") }
        } catch (e: TelegramApiException) {
            val permanent = e.code in 400..499 && e.code != 429 && e.code != 408
            db.transaction { conn ->
                if (permanent) {
                    finish(conn, delivery, "failed_at = UTC_TIMESTAMP(3)")
                    if (e.code == 403) {
                        conn.prepareStatement("UPDATE telegram_identities SET private_chat_id = NULL WHERE user_id = ? AND private_chat_id = ?").use {
                            it.setLong(1, delivery.userId)
                            it.setLong(2, delivery.chatId)
                            it.executeUpdate()
                        }
                    }
                } else retry(conn, delivery, e.retryAfter)
            }
            log.warn("Telegram message delivery failed with API status {}", e.code)
        } catch (e: java.io.IOException) {
            db.transaction { conn -> retry(conn, delivery, null) }
            log.warn("Telegram message delivery failed: {}", e.javaClass.simpleName)
        }
        return true
    }

    private fun claim(conn: Connection): Delivery? {
        val sql = """SELECT o.id, o.user_id, t.private_chat_id, o.message, o.attempts, o.open_mini_app
            FROM telegram_outbox o
            JOIN telegram_identities t ON t.user_id = o.user_id
            JOIN users u ON u.id = o.user_id
            WHERE o.sent_at IS NULL AND o.failed_at IS NULL AND o.next_attempt_at <= UTC_TIMESTAMP(3)
              AND (o.lease_until IS NULL OR o.lease_until < UTC_TIMESTAMP(3))
              AND t.private_chat_id IS NOT NULL AND u.active = TRUE
              AND NOT EXISTS (SELECT 1 FROM telegram_outbox earlier WHERE earlier.user_id = o.user_id
                  AND earlier.id < o.id AND earlier.sent_at IS NULL AND earlier.failed_at IS NULL)
            ORDER BY o.id LIMIT 1 FOR UPDATE SKIP LOCKED"""
        val lease = UUID.randomUUID().toString()
        val delivery = conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) null else Delivery(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5), lease, rs.getBoolean(6))
            }
        } ?: return null
        conn.prepareStatement("UPDATE telegram_outbox SET lease_token = ?, lease_until = UTC_TIMESTAMP(3) + INTERVAL 1 MINUTE WHERE id = ?").use {
            it.setString(1, lease)
            it.setLong(2, delivery.id)
            it.executeUpdate()
        }
        return delivery
    }

    private fun finish(conn: Connection, delivery: Delivery, assignment: String) {
        conn.prepareStatement("UPDATE telegram_outbox SET $assignment, lease_token = NULL, lease_until = NULL WHERE id = ? AND lease_token = ?").use {
            it.setLong(1, delivery.id)
            it.setString(2, delivery.lease)
            it.executeUpdate()
        }
    }

    private fun retry(conn: Connection, delivery: Delivery, retryAfter: Int?) {
        val seconds = maxOf(retryAfter?.coerceIn(1, 86400) ?: 0, (1 shl delivery.attempts.coerceIn(0, 10)).coerceAtMost(900))
        conn.prepareStatement(
            "UPDATE telegram_outbox SET attempts = attempts + 1, next_attempt_at = TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3)), lease_token = NULL, lease_until = NULL WHERE id = ? AND lease_token = ?",
        ).use {
            it.setInt(1, seconds)
            it.setLong(2, delivery.id)
            it.setString(3, delivery.lease)
            it.executeUpdate()
        }
    }
}
