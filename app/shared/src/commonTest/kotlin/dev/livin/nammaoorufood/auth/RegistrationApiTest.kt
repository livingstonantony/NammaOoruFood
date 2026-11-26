package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.network.RegisterRequest
import dev.livin.nammaoorufood.network.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegistrationApiTest {
    @Test
    fun routesRegistrationByRoleAndRejectsAdmin() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond("""{"code":"CONFLICT","message":"Username is already taken"}""", HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            defaultRequest { url("https://example.test/") }
        }
        try {
            val api = KtorAuthApi(client)
            val request = RegisterRequest("alice", "password1", "Alice")
            assertEquals("Username is already taken", assertIs<ApiResult.Rejected>(api.register(request, Role.CUSTOMER)).message)
            assertIs<ApiResult.Rejected>(api.register(request, Role.HOTEL_OWNER))
            assertIs<ApiResult.Rejected>(api.register(request, Role.ADMIN))
            assertEquals(listOf("/auth/register", "/auth/register-owner"), paths)
        } finally {
            client.close()
        }
    }
}
