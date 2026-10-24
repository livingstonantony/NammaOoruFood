package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.auth.*
import dev.livin.nammaoorufood.config.AppConfig
import dev.livin.nammaoorufood.user.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.sql.DriverManager
import kotlin.test.*

class AuthTest {

    private fun config(extra: Map<String, String> = emptyMap()) = AppConfig.fromEnv(
        System.getenv() + mapOf(
            "APP_ENV" to "test",
            "DB_URL" to "jdbc:mysql://localhost:3306/nammaoorufood_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            "LOGIN_RATE_LIMIT_PER_MINUTE" to "1000",
        ) + extra,
    )

    @BeforeTest
    fun cleanDb() {
        val c = config().database
        DriverManager.getConnection(c.url, c.user, c.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("SET FOREIGN_KEY_CHECKS = 0")
                listOf("notifications", "auth_sessions", "pickup_tokens", "order_items", "orders", "food_items", "hotels", "users").forEach { st.execute("TRUNCATE TABLE $it") }
                st.execute("SET FOREIGN_KEY_CHECKS = 1")
            }
        }
    }

    private fun ApplicationTestBuilder.setup(cfg: AppConfig = config()): HttpClient {
        application {
            module(cfg)
            routing {
                authenticate(JWT_AUTH) {
                    get("/test/admin") {
                        call.principalOrThrow().requireRole(Role.ADMIN)
                        call.respondText("ok")
                    }
                }
            }
        }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private suspend fun HttpClient.login(username: String, password: String) =
        post("/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest(username, password)) }

    @Test
    fun registerThenMeAndDuplicateUsername() = testApplication {
        val client = setup()
        val reg = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("Ravi", "secret123", "Ravi K"))
        }
        assertEquals(HttpStatusCode.Created, reg.status)
        val auth = reg.body<AuthResponse>()
        assertEquals("ravi", auth.user.username)
        assertEquals(Role.CUSTOMER, auth.user.role)

        val me = client.get("/auth/me") { bearerAuth(auth.accessToken) }
        assertEquals(HttpStatusCode.OK, me.status)

        val dup = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("ravi", "secret123", "Other"))
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)
    }

    @Test
    fun registerValidatesInput() = testApplication {
        val client = setup()
        val r = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("ab", "short", "X"))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun loginValidAndInvalid() = testApplication {
        val client = setup()
        assertEquals(HttpStatusCode.OK, client.login("customer", "customer12345").status)
        assertEquals(HttpStatusCode.Unauthorized, client.login("customer", "wrong-password").status)
        assertEquals(HttpStatusCode.Unauthorized, client.login("nobody", "whatever123").status)
    }

    @Test
    fun passwordsAreStoredHashed() = testApplication {
        setup()
        client.get("/health")
        val c = config().database
        DriverManager.getConnection(c.url, c.user, c.password).use { conn ->
            val hash = conn.createStatement().executeQuery("SELECT password_hash FROM users WHERE username='customer'").use { it.next(); it.getString(1) }
            assertTrue(hash.startsWith("\$2"))
            assertNotEquals("customer12345", hash)
        }
    }

    @Test
    fun protectedEndpointsRequireAuthAndRole() = testApplication {
        val client = setup()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/test/admin") { bearerAuth("garbage") }.status)

        val customer = client.login("customer", "customer12345").body<AuthResponse>()
        assertEquals(HttpStatusCode.Forbidden, client.get("/test/admin") { bearerAuth(customer.accessToken) }.status)

        val admin = client.login("admin", "admin12345").body<AuthResponse>()
        assertEquals(HttpStatusCode.OK, client.get("/test/admin") { bearerAuth(admin.accessToken) }.status)
    }

    @Test
    fun refreshRotatesAndOldTokenIsRejected() = testApplication {
        val client = setup()
        val first = client.login("customer", "customer12345").body<AuthResponse>()
        val refreshed = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json); setBody(RefreshRequest(first.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        val second = refreshed.body<AuthResponse>()
        assertNotEquals(first.refreshToken, second.refreshToken)

        val replay = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json); setBody(RefreshRequest(first.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertEquals(HttpStatusCode.OK, client.get("/auth/me") { bearerAuth(second.accessToken) }.status)
    }

    @Test
    fun logoutRevokesSessionAndAccessToken() = testApplication {
        val client = setup()
        val s = client.login("customer", "customer12345").body<AuthResponse>()
        val out = client.post("/auth/logout") {
            contentType(ContentType.Application.Json); setBody(RefreshRequest(s.refreshToken))
        }
        assertEquals(HttpStatusCode.NoContent, out.status)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { bearerAuth(s.accessToken) }.status)
        val refresh = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json); setBody(RefreshRequest(s.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, refresh.status)
    }

    @Test
    fun loginIsRateLimited() = testApplication {
        val client = setup(config(mapOf("LOGIN_RATE_LIMIT_PER_MINUTE" to "3")))
        val statuses = (1..5).map { client.login("customer", "bad-password").status }
        assertTrue(HttpStatusCode.TooManyRequests in statuses, "statuses=$statuses")
    }

    @Test
    fun devSeedAccountsAreDisabledWhenConfiguredOff() = testApplication {
        val client = setup(config(mapOf("SEED_DEV_ACCOUNTS" to "false")))
        assertEquals(HttpStatusCode.Unauthorized, client.login("admin", "admin12345").status)
    }

    @Test
    fun malformedBodyReturns400() = testApplication {
        val client = setup()
        val r = client.post("/auth/login") { contentType(ContentType.Application.Json); setBody("{bad") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }
}
