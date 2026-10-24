package dev.livin.nammaoorufood.notification

import kotlinx.serialization.Serializable
import java.sql.Connection
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

class JdbcNotificationRepository : NotificationRepository {
    override fun insert(conn: Connection, userId: Long, orderId: Long, type: NotificationType, title: String, message: String): Boolean =
        conn.prepareStatement("INSERT IGNORE INTO notifications (user_id, order_id, type, title, message) VALUES (?, ?, ?, ?, ?)").use {
            it.setLong(1, userId); it.setLong(2, orderId); it.setString(3, type.name); it.setString(4, title); it.setString(5, message)
            it.executeUpdate() == 1
        }

    override fun list(conn: Connection, userId: Long, unreadOnly: Boolean, limit: Int): List<NotificationDto> =
        conn.prepareStatement(
            "SELECT id, order_id, type, title, message, read_at IS NOT NULL AS is_read, created_at FROM notifications WHERE user_id = ?" +
                (if (unreadOnly) " AND read_at IS NULL" else "") + " ORDER BY created_at DESC, id DESC LIMIT ?",
        ).use {
            it.setLong(1, userId); it.setInt(2, limit)
            it.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) NotificationDto(
                        rs.getLong("id"), rs.getLong("order_id"), NotificationType.valueOf(rs.getString("type")),
                        rs.getString("title"), rs.getString("message"), rs.getBoolean("is_read"),
                        rs.getObject("created_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC).toString(),
                    ) else null
                }.toList()
            }
        }

    override fun unreadCount(conn: Connection, userId: Long): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL").use {
            it.setLong(1, userId)
            it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    override fun markRead(conn: Connection, userId: Long, id: Long): Boolean {
        conn.prepareStatement("UPDATE notifications SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP(3)) WHERE id = ? AND user_id = ?").use {
            it.setLong(1, id); it.setLong(2, userId)
            it.executeUpdate()
        }
        // Existence is checked separately because MySQL reports 0 affected rows when nothing changed.
        return conn.prepareStatement("SELECT 1 FROM notifications WHERE id = ? AND user_id = ?").use {
            it.setLong(1, id); it.setLong(2, userId)
            it.executeQuery().use { rs -> rs.next() }
        }
    }

    override fun markAllRead(conn: Connection, userId: Long) {
        conn.prepareStatement("UPDATE notifications SET read_at = CURRENT_TIMESTAMP(3) WHERE user_id = ? AND read_at IS NULL").use {
            it.setLong(1, userId)
            it.executeUpdate()
        }
    }

    override fun deleteCreatedBefore(conn: Connection, cutoff: java.time.Instant): Int =
        conn.prepareStatement("DELETE FROM notifications WHERE created_at < ?").use {
            it.setObject(1, java.time.LocalDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC))
            it.executeUpdate()
        }
}
