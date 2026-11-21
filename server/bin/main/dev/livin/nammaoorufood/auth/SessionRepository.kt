package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.database.AuthSessionsTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

data class AuthSession(val id: Long, val userId: Long)

interface SessionRepository {
    fun create(conn: Connection, userId: Long, refreshHash: String, expiresAt: Instant): Long
    fun findActiveByRefreshHash(conn: Connection, refreshHash: String, now: Instant): AuthSession?
    fun rotate(conn: Connection, sessionId: Long, oldHash: String, newHash: String, expiresAt: Instant): Boolean
    fun isActive(conn: Connection, sessionId: Long, now: Instant): Boolean
    fun revoke(conn: Connection, sessionId: Long, now: Instant)
    fun revokeByRefreshHash(conn: Connection, refreshHash: String, now: Instant)
    fun revokeAllForUser(conn: Connection, userId: Long, now: Instant)
    fun deleteInactiveBefore(conn: Connection, cutoff: Instant): Int
}

private fun Instant.utc() = LocalDateTime.ofInstant(this, ZoneOffset.UTC)

class ExposedSessionRepository : SessionRepository {
    override fun create(conn: Connection, userId: Long, refreshHash: String, expiresAt: Instant): Long =
        AuthSessionsTable.insert {
            it[AuthSessionsTable.userId] = userId
            it[refreshTokenHash] = refreshHash
            it[AuthSessionsTable.expiresAt] = expiresAt.utc()
        }[AuthSessionsTable.id]

    override fun findActiveByRefreshHash(conn: Connection, refreshHash: String, now: Instant): AuthSession? =
        AuthSessionsTable.selectAll()
            .where {
                (AuthSessionsTable.refreshTokenHash eq refreshHash) and
                    AuthSessionsTable.revokedAt.isNull() and
                    (AuthSessionsTable.expiresAt greater now.utc())
            }
            .forUpdate()
            .limit(1)
            .singleOrNull()
            ?.let { AuthSession(it[AuthSessionsTable.id], it[AuthSessionsTable.userId]) }

    override fun rotate(conn: Connection, sessionId: Long, oldHash: String, newHash: String, expiresAt: Instant): Boolean =
        AuthSessionsTable.update({
            (AuthSessionsTable.id eq sessionId) and
                (AuthSessionsTable.refreshTokenHash eq oldHash) and
                AuthSessionsTable.revokedAt.isNull()
        }) {
            it[refreshTokenHash] = newHash
            it[AuthSessionsTable.expiresAt] = expiresAt.utc()
        } == 1

    override fun isActive(conn: Connection, sessionId: Long, now: Instant): Boolean =
        AuthSessionsTable.selectAll().where {
            (AuthSessionsTable.id eq sessionId) and
                AuthSessionsTable.revokedAt.isNull() and
                (AuthSessionsTable.expiresAt greater now.utc())
        }.limit(1).singleOrNull() != null

    override fun revoke(conn: Connection, sessionId: Long, now: Instant) {
        AuthSessionsTable.update({
            (AuthSessionsTable.id eq sessionId) and AuthSessionsTable.revokedAt.isNull()
        }) {
            it[revokedAt] = now.utc()
        }
    }

    override fun revokeByRefreshHash(conn: Connection, refreshHash: String, now: Instant) {
        AuthSessionsTable.update({
            (AuthSessionsTable.refreshTokenHash eq refreshHash) and AuthSessionsTable.revokedAt.isNull()
        }) {
            it[revokedAt] = now.utc()
        }
    }

    override fun revokeAllForUser(conn: Connection, userId: Long, now: Instant) {
        AuthSessionsTable.update({
            (AuthSessionsTable.userId eq userId) and AuthSessionsTable.revokedAt.isNull()
        }) {
            it[revokedAt] = now.utc()
        }
    }

    override fun deleteInactiveBefore(conn: Connection, cutoff: Instant): Int {
        val time = cutoff.utc()
        return AuthSessionsTable.deleteWhere {
            (expiresAt less time) or (revokedAt less time)
        }
    }
}
