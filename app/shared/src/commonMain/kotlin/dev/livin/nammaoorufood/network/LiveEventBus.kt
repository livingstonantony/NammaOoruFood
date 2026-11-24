package dev.livin.nammaoorufood.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class LiveEvent(
    val type: String,
    val id: Long? = null,
    val payload: String? = null,
)

class LiveEventBus(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    init {
        startListening()
    }

    private fun startListening() {
        scope.launch {
            var backoffMs = 1000L
            val wsUrl = buildWsUrl(baseUrl)
            while (true) {
                runCatching {
                    client.webSocket(wsUrl) {
                        backoffMs = 1000L
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                runCatching {
                                    val event = ApiJson.decodeFromString<LiveEvent>(text)
                                    _events.tryEmit(event)
                                }
                            }
                        }
                    }
                }.onFailure {
                    // Ignore disconnects and retry with backoff
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
            }
        }
    }

    private fun buildWsUrl(baseUrl: String): String {
        val clean = baseUrl.trimEnd('/')
        val wsScheme = when {
            clean.startsWith("https://", ignoreCase = true) -> "wss://" + clean.substring(8)
            clean.startsWith("http://", ignoreCase = true) -> "ws://" + clean.substring(7)
            else -> "ws://$clean"
        }
        return "$wsScheme/ws/events"
    }
}
