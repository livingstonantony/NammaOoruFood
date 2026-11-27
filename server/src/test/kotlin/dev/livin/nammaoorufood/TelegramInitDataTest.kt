package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.plugins.UnauthorizedException
import dev.livin.nammaoorufood.telegram.TelegramInitDataVerifier
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import dev.livin.nammaoorufood.auth.AuthResponse
import dev.livin.nammaoorufood.auth.TelegramLoginRequest
import dev.livin.nammaoorufood.config.AppConfig
import dev.livin.nammaoorufood.user.Role
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class TelegramInitDataTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val verifier = TelegramInitDataVerifier("123:test-token", Clock.fixed(now, ZoneOffset.UTC))

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }

    private fun signed(timestamp: Long = now.epochSecond, user: String = """{"id":123,"first_name":"Ravi"}"""): String {
        val fields = sortedMapOf("auth_date" to timestamp.toString(), "user" to user)
        val check = fields.entries.joinToString("\n") { "${it.key}=${it.value}" }
        val secret = hmac("WebAppData".toByteArray(), "123:test-token".toByteArray())
        val hash = hmac(secret, check.toByteArray()).joinToString("") { "%02x".format(it) }
        return fields.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}"
        } + "&hash=$hash"
    }

    @Test
    fun acceptsValidSignedUser() {
        val user = verifier.verify(signed())
        assertEquals(123L, user.id)
        assertEquals("Ravi", user.displayName)
    }

    @Test
    fun rejectsTamperingDuplicatesAndStaleOrFutureData() {
        val valid = signed()
        assertFailsWith<UnauthorizedException> { verifier.verify(valid.replace("Ravi", "Evil")) }
        assertFailsWith<UnauthorizedException> { verifier.verify("$valid&auth_date=1") }
        assertFailsWith<UnauthorizedException> { verifier.verify(signed(now.epochSecond - 301)) }
        assertFailsWith<UnauthorizedException> { verifier.verify(signed(now.epochSecond + 1)) }
        assertFailsWith<UnauthorizedException> { verifier.verify(signed(user = """{"id":0,"first_name":"Ravi"}""")) }
    }

    @Test
    fun telegramLoginCreatesAndReusesSeparateCustomer() = testApplication {
        val config = AppConfig.fromEnv(System.getenv() + mapOf(
            "APP_ENV" to "test",
            "DB_URL" to testDatabaseUrl(),
            "TELEGRAM_BOT_TOKEN" to "123:test-token",
            "TELEGRAM_BOT_POLLING_ENABLED" to "false",
            "LOGIN_RATE_LIMIT_PER_MINUTE" to "1000",
        ))
        application { module(config, testImageKit()) }
        val http = createClient { install(ContentNegotiation) { json() } }
        http.get("/health")
        val db = config.database
        val telegramId = 987654321L
        fun clearIdentity() {
            DriverManager.getConnection(db.url, db.user, db.password).use { conn ->
                conn.prepareStatement("DELETE FROM telegram_identities WHERE telegram_user_id = ?").use {
                    it.setLong(1, telegramId)
                    it.executeUpdate()
                }
            }
        }
        clearIdentity()
        try {
            val data = signed(Instant.now().epochSecond, """{"id":$telegramId,"first_name":"Ravi"}""")
            suspend fun login() = http.post("/auth/telegram") {
                contentType(ContentType.Application.Json)
                setBody(TelegramLoginRequest(data))
            }
            val responses = coroutineScope { (1..3).map { async { login() } }.awaitAll() }
            assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK), responses.map { it.status })
            val accounts = responses.map { it.body<AuthResponse>().user }
            val account = accounts.first()
            assertEquals(Role.CUSTOMER, account.role)
            assertEquals(null, account.username)
            assertEquals(1, accounts.map { it.id }.distinct().size)
            assertEquals(account.id, login().body<AuthResponse>().user.id)
            assertEquals(HttpStatusCode.Unauthorized, http.post("/auth/telegram") {
                contentType(ContentType.Application.Json)
                setBody(TelegramLoginRequest(data.replace("Ravi", "Evil")))
            }.status)
            DriverManager.getConnection(db.url, db.user, db.password).use { conn ->
                conn.prepareStatement("UPDATE users SET active = FALSE WHERE id = ?").use {
                    it.setLong(1, account.id)
                    it.executeUpdate()
                }
            }
            assertEquals(HttpStatusCode.Unauthorized, login().status)
        } finally {
            clearIdentity()
        }
    }
}
