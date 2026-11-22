package dev.livin.nammaoorufood.websocket

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

fun Route.websocketRoutes() {
    webSocket("/ws/events") {
        ServerEventBus.register(this)
        try {
            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    if (text.equals("ping", ignoreCase = true)) {
                        send(Frame.Text("pong"))
                    }
                }
            }
        } finally {
            ServerEventBus.unregister(this)
        }
    }
}
