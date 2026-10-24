package dev.livin.nammaoorufood.notification

import dev.livin.nammaoorufood.auth.JWT_AUTH
import dev.livin.nammaoorufood.auth.principalOrThrow
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.plugins.NotFoundException
import dev.livin.nammaoorufood.plugins.ValidationException
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class NotificationService(private val db: Database, private val repo: NotificationRepository) {
    fun list(userId: Long, unreadOnly: Boolean, limit: Int): NotificationPage = db.transaction { conn ->
        NotificationPage(repo.list(conn, userId, unreadOnly, limit.coerceIn(1, 100)), repo.unreadCount(conn, userId))
    }

    fun markRead(userId: Long, id: Long) = db.transaction { conn ->
        if (!repo.markRead(conn, userId, id)) throw NotFoundException("Notification not found")
    }

    fun markAllRead(userId: Long) = db.transaction { repo.markAllRead(it, userId) }
}

fun Route.notificationRoutes(service: NotificationService) {
    authenticate(JWT_AUTH) {
        route("/notifications") {
            get {
                val q = call.request.queryParameters
                call.respond(service.list(call.principalOrThrow().userId, q["unread"]?.toBoolean() ?: false, q["limit"]?.toIntOrNull() ?: 50))
            }
            post("/read-all") {
                service.markAllRead(call.principalOrThrow().userId)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/{id}/read") {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid id")
                service.markRead(call.principalOrThrow().userId, id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
