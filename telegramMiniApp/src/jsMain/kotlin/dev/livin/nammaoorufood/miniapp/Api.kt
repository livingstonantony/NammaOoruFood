package dev.livin.nammaoorufood.miniapp

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.js.Promise

@Serializable data class AppConfig(val apiBaseUrl: String = "")
@Serializable data class User(val id: Long, val displayName: String, val role: String)
@Serializable data class Session(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long, val user: User)
@Serializable data class InitRequest(val initData: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class ApiError(val message: String)
@Serializable data class Hotel(val id: Long, val name: String, val description: String? = null, val address: String, val isOpen: Boolean)
@Serializable data class HotelPage(val items: List<Hotel>, val page: Int, val pageSize: Int, val total: Int)
@Serializable data class FoodItem(val id: Long, val name: String, val description: String? = null, val price: String, val quantityAvailable: Int? = null)
@Serializable data class Menu(val items: List<FoodItem>)
@Serializable data class OrderLine(val foodItemId: Long, val quantity: Int)
@Serializable data class PlaceOrder(val hotelId: Long, val items: List<OrderLine>, val note: String? = null)
@Serializable data class Order(val id: Long, val hotelName: String, val status: String, val total: String, val pickupToken: Int? = null)
@Serializable data class OrderPage(val items: List<Order>, val page: Int, val pageSize: Int, val total: Int)
@Serializable data class PendingOrder(val customerId: Long, val hotel: Hotel, val menu: List<FoodItem>, val request: PlaceOrder, val key: String)

class ApiException(val status: Int, message: String) : Exception(message)

interface CustomerClient {
    val customerId: Long
    suspend fun hotels(page: Int): HotelPage
    suspend fun menu(id: Long): Menu
    suspend fun orders(): OrderPage
    suspend fun cancel(id: Long): Order
    suspend fun place(order: PlaceOrder, key: String): Order
}

class CustomerApi(private val base: String, private var session: Session) : CustomerClient {
    override val customerId: Long get() = session.user.id
    private val refreshLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun hotels(page: Int): HotelPage = json.decodeFromString(request("/hotels?openOnly=true&page=$page&pageSize=20"))
    override suspend fun menu(id: Long): Menu = json.decodeFromString(request("/hotels/$id/menu"))
    override suspend fun orders(): OrderPage = json.decodeFromString(request("/orders?page=1&pageSize=50"))
    override suspend fun cancel(id: Long): Order = json.decodeFromString(request("/orders/$id/cancel", "POST"))
    override suspend fun place(order: PlaceOrder, key: String): Order =
        json.decodeFromString(request("/orders", "POST", json.encodeToString(order), key))

    private suspend fun request(path: String, method: String = "GET", body: String? = null, key: String? = null): String {
        val token = session.accessToken
        val first = send(base + path, method, body, token, key)
        if (first.status != 401) return first.requireSuccess()
        refreshLock.withLock {
            if (session.accessToken == token) {
                val response = send(base + "/auth/refresh", "POST", json.encodeToString(RefreshRequest(session.refreshToken)))
                session = json.decodeFromString(response.requireSuccess())
            }
        }
        return send(base + path, method, body, session.accessToken, key).requireSuccess()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        suspend fun connect(initData: String): CustomerApi {
            if (initData.isBlank()) throw ApiException(0, "Open this app from Telegram to sign in.")
            val config = json.decodeFromString<AppConfig>(send("/mini-app-config.json").requireSuccess())
            val base = config.apiBaseUrl.trim().trimEnd('/')
            val url = js("new URL(base || window.location.origin, window.location.origin)")
            val protocol = url.protocol as String
            val host = url.hostname as String
            if (protocol != "https:" && !(protocol == "http:" && (host == "localhost" || host == "127.0.0.1"))) {
                throw ApiException(0, "The API URL must use HTTPS.")
            }
            val origin = url.origin as String
            val path = url.pathname as String
            if (url.username != "" || url.password != "" || url.search != "" || url.hash != "" || (path != "/" && path.isNotEmpty())) {
                throw ApiException(0, "Configure the API URL as an origin without a path.")
            }
            val response = send(origin + "/auth/telegram", "POST", json.encodeToString(InitRequest(initData)))
            val session = json.decodeFromString<Session>(response.requireSuccess())
            if (session.user.role != "CUSTOMER") throw ApiException(0, "A customer account is required.")
            return CustomerApi(origin, session)
        }

        private data class Response(val status: Int, val body: String) {
            fun requireSuccess(): String {
                if (status in 200..299) return body
                val message = try { json.decodeFromString<ApiError>(body).message } catch (_: IllegalArgumentException) { "Request failed. Please try again." }
                throw ApiException(status, message)
            }
        }

        private suspend fun send(url: String, method: String = "GET", body: String? = null, token: String? = null, key: String? = null): Response {
            val options = js("({})")
            options.method = method
            val headers = js("({})")
            if (body != null) { headers["Content-Type"] = "application/json"; options.body = body }
            if (token != null) headers.Authorization = "Bearer $token"
            if (key != null) headers["Idempotency-Key"] = key
            options.headers = headers
            options.credentials = "omit"
            options.cache = "no-store"
            val controller = js("new AbortController()")
            options.signal = controller.signal
            val timeout = window.setTimeout({ controller.abort() }, 20_000)
            try {
                val response = (window.asDynamic().fetch(url, options) as Promise<dynamic>).await()
                val text = (response.text() as Promise<String>).await()
                return Response(response.status as Int, text)
            } finally {
                window.clearTimeout(timeout)
            }
        }
    }
}

private val pendingJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
private const val PENDING_ORDER_KEY = "namma-telegram-pending-order"

fun savePendingOrder(order: PendingOrder) {
    window.sessionStorage.setItem(PENDING_ORDER_KEY, pendingJson.encodeToString(order))
}

fun readPendingOrder(customerId: Long): PendingOrder? {
    val value = window.sessionStorage.getItem(PENDING_ORDER_KEY) ?: return null
    val order = pendingJson.decodeFromString<PendingOrder>(value)
    if (order.customerId == customerId) return order
    window.sessionStorage.removeItem(PENDING_ORDER_KEY)
    return null
}

fun clearPendingOrder() = window.sessionStorage.removeItem(PENDING_ORDER_KEY)

fun telegramInitData(): String = (window.asDynamic().Telegram?.WebApp?.initData as? String).orEmpty()

fun prepareTelegram() {
    val webApp = window.asDynamic().Telegram?.WebApp
    if (webApp != null) {
        webApp.ready()
        webApp.expand()
    }
}

fun newOrderKey(): String = js("Array.from(crypto.getRandomValues(new Uint8Array(16)), x => x.toString(16).padStart(2, '0')).join('')") as String

fun estimatedTotal(items: List<FoodItem>, quantities: Map<Long, Int>): String {
    val paise = items.sumOf { item ->
        val parts = item.price.split('.')
        val unit = parts[0].toLong() * 100 + (parts.getOrNull(1) ?: "0").padEnd(2, '0').take(2).toLong()
        unit * (quantities[item.id] ?: 0)
    }
    return "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"
}
