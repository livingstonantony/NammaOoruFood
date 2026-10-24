package dev.livin.nammaoorufood.maintenance

import dev.livin.nammaoorufood.auth.SessionRepository
import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.notification.NotificationRepository
import java.time.Clock
import java.time.Duration

data class RetentionResult(val sessionsDeleted: Int, val notificationsDeleted: Int)

/** Deletes expired/revoked refresh sessions and old notifications. Orders are never deleted (billing history). */
class RetentionService(
    private val db: Database,
    private val sessions: SessionRepository,
    private val notifications: NotificationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val sessionGrace: Duration = Duration.ofDays(7),
    private val notificationRetention: Duration = Duration.ofDays(90),
) {
    fun runOnce(): RetentionResult = db.transaction { conn ->
        val now = clock.instant()
        RetentionResult(
            sessions.deleteInactiveBefore(conn, now - sessionGrace),
            notifications.deleteCreatedBefore(conn, now - notificationRetention),
        )
    }
}
