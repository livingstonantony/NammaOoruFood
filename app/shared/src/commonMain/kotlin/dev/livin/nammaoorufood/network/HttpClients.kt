package dev.livin.nammaoorufood.network

import dev.livin.nammaoorufood.auth.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

val ApiJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

private fun HttpClientConfig<*>.common(baseUrl: String) {
    expectSuccess = false
    install(ContentNegotiation) { json(ApiJson) }
    install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 10_000 }
    defaultRequest { url(baseUrl.trimEnd('/') + "/") }
}

/** Plain client used for /auth calls. */
fun createPublicClient(engine: HttpClientEngine, baseUrl: String) = HttpClient(engine) { common(baseUrl) }

/** Client for protected endpoints; on 401 it refreshes once via [SessionManager] and retries. */
fun createAuthenticatedClient(engine: HttpClientEngine, baseUrl: String, session: SessionManager) =
    HttpClient(engine) {
        common(baseUrl)
        install(Auth) {
            bearer {
                loadTokens { session.currentAccessToken()?.let { BearerTokens(it, null) } }
                refreshTokens {
                    session.refreshAccessToken(oldTokens?.accessToken)?.let { BearerTokens(it, null) }
                }
                sendWithoutRequest { true }
            }
        }
    }
