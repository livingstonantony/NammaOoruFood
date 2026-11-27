package dev.livin.nammaoorufood.miniapp

import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomerApiTest {
    @Test
    fun signsInWithTelegramDataAndRequestsHotelsFromConfiguredApi(): Promise<Unit> = MainScope().promise {
        val original = window.asDynamic().fetch
        val urls = mutableListOf<String>()
        var authBody = ""
        val fakeFetch: (String, dynamic) -> Promise<dynamic> = { url, options ->
            urls.add(url)
            val response = js("({})")
            response.status = 200
            val body = when (url) {
                "/mini-app-config.json" -> """{"apiBaseUrl":"https://api.example.com"}"""
                "https://api.example.com/auth/telegram" -> {
                    authBody = options.body as String
                    """{"accessToken":"access","refreshToken":"refresh","expiresInSeconds":900,"user":{"id":1,"displayName":"Ravi","role":"CUSTOMER"}}"""
                }
                "https://api.example.com/hotels?openOnly=true&page=1&pageSize=20" ->
                    """{"items":[],"page":1,"pageSize":20,"total":0}"""
                else -> error("Unexpected request: $url")
            }
            val text: () -> Promise<String> = { Promise.resolve(body) }
            response.text = text
            Promise.resolve<dynamic>(response)
        }
        window.asDynamic().fetch = fakeFetch
        try {
            val client = CustomerApi.connect("signed-init-data")
            assertEquals(1L, client.customerId)
            assertEquals(0, client.hotels(1).total)
            assertEquals("""{"initData":"signed-init-data"}""", authBody)
            assertEquals(listOf("/mini-app-config.json", "https://api.example.com/auth/telegram",
                "https://api.example.com/hotels?openOnly=true&page=1&pageSize=20"), urls)
        } finally {
            window.asDynamic().fetch = original
        }
    }

    @Test
    fun refreshesAndRetriesOrderWithSameIdempotencyKey(): Promise<Unit> = MainScope().promise {
        val original = window.asDynamic().fetch
        val keys = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        var refreshCount = 0
        val fakeFetch: (String, dynamic) -> Promise<dynamic> = { url, options ->
            val response = js("({})")
            val body: String
            if (url.endsWith("/auth/refresh")) {
                refreshCount++
                response.status = 200
                body = """{"accessToken":"new","refreshToken":"rotated","expiresInSeconds":900,"user":{"id":1,"displayName":"Ravi","role":"CUSTOMER"}}"""
            } else {
                keys.add(options.headers["Idempotency-Key"] as String)
                tokens.add(options.headers.Authorization as String)
                response.status = if (keys.size == 1) 401 else 201
                body = if (keys.size == 1) """{"message":"expired"}""" else
                    """{"id":42,"hotelName":"Hotel","status":"PLACED","total":"25.00"}"""
            }
            val text: () -> Promise<String> = { Promise.resolve(body) }
            response.text = text
            Promise.resolve<dynamic>(response)
        }
        window.asDynamic().fetch = fakeFetch
        try {
            val api = CustomerApi("https://api.example.com", Session("old", "refresh", 900, User(1, "Ravi", "CUSTOMER")))
            val result = api.place(PlaceOrder(2, listOf(OrderLine(3, 1))), "retry-key-123456")
            assertEquals(42L, result.id)
            assertEquals(listOf("retry-key-123456", "retry-key-123456"), keys)
            assertEquals(listOf("Bearer old", "Bearer new"), tokens)
            assertEquals(1, refreshCount)
        } finally {
            window.asDynamic().fetch = original
        }
    }
}
