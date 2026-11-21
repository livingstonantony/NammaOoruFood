package dev.livin.nammaoorufood.notification

import dev.livin.nammaoorufood.database.NotificationsTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SortOrder
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

enum class NotificationType { ORDER_ACCEPTED, ORDER_REJECTED, ORDER_PREPARING, ORDER_READY }

@Serializable
data class NotificationDto(
    val id: Long,
    val orderId: Long,
    val type: NotificationType,
    val title: String,
    val message: String,
    val read: Boolean,
    val createdAt: String,
)

@Serializable
data class NotificationPage(val items: List<NotificationDto>, val unreadCount: Int)

interface NotificationRepository {
    // Returns false if this (order, type) notification already exists, making delivery idempotent.
    fun insert(conn: Connection, userId: Long, orderId: Long, type: NotificationType, title: String, message: String): Boolean
    fun list(conn: Connection, userId: Long, unreadOnly: Boolean, limit: Int): List<NotificationDto>
    fun unreadCount(conn: Connection, userId: Long): Int
    fun markRead(conn: Connection, userId: Long, id: Long): Boolean
    fun markAllRead(conn: Connection, userId: Long)
    fun deleteCreatedBefore(conn: Connection, cutoff: java.time.Instant): Int
}

class ExposedNotificationRepository : NotificationRepository {
    override fun insert(conn: Connection, userId: Long, orderId: Long, type: NotificationType, title: String, message: String): Boolean =
        NotificationsTable.insertIgnore {
            it[NotificationsTable.userId] = userId
            it[NotificationsTable.orderId] = orderId
            it[NotificationsTable.type] = type
            it[NotificationsTable.title] = title
            it[NotificationsTable.message] = message
        }.insertedCount == 1

    override fun list(conn: Connection, userId: Long, unreadOnly: Boolean, limit: Int): List<NotificationDto> =
        NotificationsTable.selectAll().where {
            (NotificationsTable.userId eq userId) and
                if (unreadOnly) NotificationsTable.readAt.isNull() else org.jetbrains.exposed.sql.Op.TRUE
        }
            .orderBy(NotificationsTable.createdAt to SortOrder.DESC, NotificationsTable.id to SortOrder.DESC)
            .limit(limit)
            .map {
                NotificationDto(
                    it[NotificationsTable.id],
                    it[NotificationsTable.orderId],
                    it[NotificationsTable.type],
                    it[NotificationsTable.title],
                    it[NotificationsTable.message],
                    it[NotificationsTable.readAt] != null,
                    it[NotificationsTable.createdAt].toInstant(ZoneOffset.UTC).toString(),
                )
            }

    override fun unreadCount(conn: Connection, userId: Long): Int =
        NotificationsTable.selectAll()
            .where { (NotificationsTable.userId eq userId) and NotificationsTable.readAt.isNull() }
            .count().toInt()

    override fun markRead(conn: Connection, userId: Long, id: Long): Boolean {
        NotificationsTable.update({
            (NotificationsTable.id eq id) and
                (NotificationsTable.userId eq userId) and
                NotificationsTable.readAt.isNull()
        }) {
            it[readAt] = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
        }
        return NotificationsTable.selectAll().where {
            (NotificationsTable.id eq id) and (NotificationsTable.userId eq userId)
        }.limit(1).singleOrNull() != null
    }

    override fun markAllRead(conn: Connection, userId: Long) {
        NotificationsTable.update({
            (NotificationsTable.userId eq userId) and NotificationsTable.readAt.isNull()
        }) {
            it[readAt] = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
        }
    }

    override fun deleteCreatedBefore(conn: Connection, cutoff: Instant): Int =
        NotificationsTable.deleteWhere {
            createdAt less LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC)
        }
}
