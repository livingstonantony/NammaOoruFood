package dev.livin.nammaoorufood.di

import androidx.compose.runtime.Composable
import dev.livin.nammaoorufood.auth.SessionStore
import io.ktor.client.engine.HttpClientEngine

interface PlatformServices {
    val sessionStore: SessionStore
    val engine: HttpClientEngine
    val baseUrl: String
}

@Composable
expect fun rememberPlatformServices(): PlatformServices
