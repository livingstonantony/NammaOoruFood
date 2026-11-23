package dev.livin.nammaoorufood.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.livin.nammaoorufood.auth.InMemorySessionStore
import dev.livin.nammaoorufood.auth.SessionStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js
import kotlinx.browser.window

/**
 * Browser storage is script-readable, so tokens are deliberately kept in memory only.
 * Persistent browser sessions will use server-managed HttpOnly cookies with the admin dashboard.
 */
private class WebPlatformServices : PlatformServices {
    override val sessionStore: SessionStore = InMemorySessionStore()
    override val engine: HttpClientEngine = Js.create()
    override val baseUrl: String = runCatching {
        val location = window.location
        val host = location.hostname.takeIf { it.isNotBlank() } ?: "localhost"
        val protocol = location.protocol.takeIf { it.isNotBlank() } ?: "http:"
        "$protocol//$host:8080"
    }.getOrDefault("http://localhost:8080")
}

@Composable
actual fun rememberPlatformServices(): PlatformServices = remember { WebPlatformServices() }

