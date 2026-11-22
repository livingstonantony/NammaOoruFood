package dev.livin.nammaoorufood.websocket

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class LiveEvent(
    val type: String,
    val id: Long? = null,
    val payload: String? = null,
)

object ServerEventBus {
    private val log = LoggerFactory.getLogger(ServerEventBus::class.java)
    private val sessions = ConcurrentHashMap.newKeySet<WebSocketServerSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun register(session: WebSocketServerSession) {
        sessions.add(session)
        log.info("WebSocket client connected. Total active: ${sessions.size}")
    }

    fun unregister(session: WebSocketServerSession) {
        sessions.remove(session)
        log.info("WebSocket client disconnected. Total active: ${sessions.size}")
    }

    fun broadcast(type: String, id: Long? = null, payload: String? = null) {
        val event = LiveEvent(type, id, payload)
        val text = json.encodeToString(event)
        scope.launch {
            sessions.forEach { session ->
                runCatching {
                    session.send(Frame.Text(text))
                }.onFailure {
                    sessions.remove(session)
                }
            }
        }
    }
}
