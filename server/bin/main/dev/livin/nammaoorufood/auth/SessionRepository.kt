package dev.livin.nammaoorufood.auth

import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset

data class AuthSession(val id: Long, val userId: Long)

interface SessionRepository {
    fun create(conn: Connection, userId: Long, refreshHash: String, expiresAt: Instant): Long
    fun findActiveByRefreshHash(conn: Connection, refreshHash: String, now: Instant): AuthSession?
    // Returns false if the old hash was already rotated/revoked, so a refresh token can be used only once.
    fun rotate(conn: Connection, sessionId: Long, oldHash: String, newHash: String, expiresAt: Instant): Boolean
    fun isActive(conn: Connection, sessionId: Long, now: Instant): Boolean
    fun revoke(conn: Connection, sessionId: Long, now: Instant)
    fun revokeByRefreshHash(conn: Connection, refreshHash: String, now: Instant)
    fun revokeAllForUser(conn: Connection, userId: Long, now: Instant)
    // Removes sessions that expired or were revoked before [cutoff]; returns the number deleted.
    fun deleteInactiveBefore(conn: Connection, cutoff: Instant): Int
}

private fun Instant.utc() = java.time.LocalDateTime.ofInstant(this, ZoneOffset.UTC)

class JdbcSessionRepository : SessionRepository {
    override fun create(conn: Connection, userId: Long, refreshHash: String, expiresAt: Instant): Long =
        conn.prepareStatement(
            "INSERT INTO auth_sessions (user_id, refresh_token_hash, expires_at) VALUES (?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setLong(1, userId)
            it.setString(2, refreshHash)
            it.setObject(3, expiresAt.utc())
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }

    override fun findActiveByRefreshHash(conn: Connection, refreshHash: String, now: Instant): AuthSession? =
        conn.prepareStatement(
            "SELECT id, user_id FROM auth_sessions WHERE refresh_token_hash = ? AND revoked_at IS NULL AND expires_at > ? FOR UPDATE",
        ).use {
            it.setString(1, refreshHash)
            it.setObject(2, now.utc())
            it.executeQuery().use { rs -> if (rs.next()) AuthSession(rs.getLong("id"), rs.getLong("user_id")) else null }
        }

    override fun rotate(conn: Connection, sessionId: Long, oldHash: String, newHash: String, expiresAt: Instant): Boolean =
        conn.prepareStatement(
            "UPDATE auth_sessions SET refresh_token_hash = ?, expires_at = ? WHERE id = ? AND refresh_token_hash = ? AND revoked_at IS NULL",
        ).use {
            it.setString(1, newHash)
            it.setObject(2, expiresAt.utc())
            it.setLong(3, sessionId)
            it.setString(4, oldHash)
            it.executeUpdate() == 1
        }

    override fun isActive(conn: Connection, sessionId: Long, now: Instant): Boolean =
        conn.prepareStatement("SELECT 1 FROM auth_sessions WHERE id = ? AND revoked_at IS NULL AND expires_at > ?").use {
            it.setLong(1, sessionId)
            it.setObject(2, now.utc())
            it.executeQuery().use { rs -> rs.next() }
        }

    override fun revoke(conn: Connection, sessionId: Long, now: Instant) {
        conn.prepareStatement("UPDATE auth_sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").use {
            it.setObject(1, now.utc())
            it.setLong(2, sessionId)
            it.executeUpdate()
        }
    }

    override fun revokeByRefreshHash(conn: Connection, refreshHash: String, now: Instant) {
        conn.prepareStatement("UPDATE auth_sessions SET revoked_at = ? WHERE refresh_token_hash = ? AND revoked_at IS NULL").use {
            it.setObject(1, now.utc())
            it.setString(2, refreshHash)
            it.executeUpdate()
        }
    }

    override fun revokeAllForUser(conn: Connection, userId: Long, now: Instant) {
        conn.prepareStatement("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL").use {
            it.setObject(1, now.utc())
            it.setLong(2, userId)
            it.executeUpdate()
        }
    }

    override fun deleteInactiveBefore(conn: Connection, cutoff: Instant): Int =
        conn.prepareStatement("DELETE FROM auth_sessions WHERE expires_at < ? OR revoked_at < ?").use {
            it.setObject(1, cutoff.utc())
            it.setObject(2, cutoff.utc())
            it.executeUpdate()
        }
}
