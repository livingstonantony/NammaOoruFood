package dev.livin.nammaoorufood.network

import dev.livin.nammaoorufood.auth.AuthState
import dev.livin.nammaoorufood.auth.InMemorySessionStore
import dev.livin.nammaoorufood.auth.KtorAuthApi
import dev.livin.nammaoorufood.auth.LoginOutcome
import dev.livin.nammaoorufood.auth.SessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthenticatedClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun authBody(n: Int) =
        """{"accessToken":"access$n","refreshToken":"refresh$n","expiresInSeconds":900,"user":{"id":1,"username":"customer","displayName":"Customer","role":"CUSTOMER"},"extra":1}"""

    private class Rig(val engine: MockEngine, val manager: SessionManager, val store: InMemorySessionStore)

    private fun rig(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): Rig {
        val engine = MockEngine(handler)
        val store = InMemorySessionStore()
        val manager = SessionManager(KtorAuthApi(createPublicClient(engine, "http://test")), store)
        return Rig(engine, manager, store)
    }

    @Test
    fun loginParsesServerResponseAndSendsBearerToken() = runTest {
        val seen = mutableListOf<String?>()
        val r = rig { req ->
            when (req.url.encodedPath) {
                "/auth/login" -> respond(authBody(1), HttpStatusCode.OK, json)
                else -> { seen += req.headers[HttpHeaders.Authorization]; respond("{}", HttpStatusCode.OK, json) }
            }
        }
        assertEquals(LoginOutcome.Success, r.manager.login("customer", "pw"))
        createAuthenticatedClient(r.engine, "http://test", r.manager).get("/orders")
        assertEquals(listOf<String?>("Bearer access1"), seen)
    }

    @Test
    fun expiredAccessTokenIsRefreshedOnceAndRequestRetried() = runTest {
        var refreshes = 0
        val r = rig { req ->
            when (req.url.encodedPath) {
                "/auth/login" -> respond(authBody(1), HttpStatusCode.OK, json)
                "/auth/refresh" -> { refreshes++; respond(authBody(2), HttpStatusCode.OK, json) }
                else ->
                    if (req.headers[HttpHeaders.Authorization] == "Bearer access2") respond("{}", HttpStatusCode.OK, json)
                    else respond("{}", HttpStatusCode.Unauthorized, json)
            }
        }
        r.manager.login("customer", "pw")
        val response = createAuthenticatedClient(r.engine, "http://test", r.manager).get("/orders")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, refreshes)
        assertEquals("refresh2", r.store.load()?.refreshToken)
    }

    @Test
    fun revokedSessionDoesNotLoopAndForcesReauthentication() = runTest {
        var refreshes = 0
        var protectedCalls = 0
        val r = rig { req ->
            when (req.url.encodedPath) {
                "/auth/login" -> respond(authBody(1), HttpStatusCode.OK, json)
                "/auth/refresh" -> { refreshes++; respond("""{"code":"UNAUTHORIZED","message":"revoked"}""", HttpStatusCode.Unauthorized, json) }
                else -> { protectedCalls++; respond("{}", HttpStatusCode.Unauthorized, json) }
            }
        }
        r.manager.login("customer", "pw")
        val response = createAuthenticatedClient(r.engine, "http://test", r.manager).get("/orders")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, refreshes)
        assertEquals(1, protectedCalls)
        assertTrue(r.manager.state.value is AuthState.ReauthenticationRequired)
        assertNull(r.store.load())
    }

    @Test
    fun serverErrorMessageSurfacesOnFailedLogin() = runTest {
        val r = rig { respond("""{"code":"RATE_LIMITED","message":"Too many attempts"}""", HttpStatusCode.TooManyRequests, json) }
        assertEquals(LoginOutcome.Failure("Too many attempts"), r.manager.login("a", "b"))
    }

    @Test
    fun unreachableServerGivesConnectionMessage() = runTest {
        val r = rig { throw RuntimeException("connect failed") }
        assertTrue((r.manager.login("a", "b") as LoginOutcome.Failure).message.contains("Cannot reach"))
    }
}
