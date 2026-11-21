package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.database.Database
import dev.livin.nammaoorufood.plugins.ConflictException
import dev.livin.nammaoorufood.plugins.UnauthorizedException
import dev.livin.nammaoorufood.plugins.ValidationException
import dev.livin.nammaoorufood.user.Role
import dev.livin.nammaoorufood.user.User
import dev.livin.nammaoorufood.user.UserRepository
import dev.livin.nammaoorufood.user.UserDto
import dev.livin.nammaoorufood.user.toDto
import kotlinx.serialization.Serializable
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Clock

@Serializable
data class RegisterRequest(val username: String, val password: String, val displayName: String, val phone: String? = null)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long, val user: UserDto)

class AuthService(
    private val db: Database,
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val hasher: PasswordHasher,
    private val tokens: TokenService,
    private val clock: Clock = Clock.systemUTC(),
) {
    // Verified against unknown usernames so response time does not reveal which usernames exist.
    private val dummyHash = hasher.hash("dummy-password-for-timing")

    fun registerCustomer(req: RegisterRequest): AuthResponse {
        val username = req.username.trim().lowercase()
        validateUsername(username)
        validatePassword(req.password)
        val displayName = req.displayName.trim()
        if (displayName.isEmpty() || displayName.length > 100) throw ValidationException("Display name must be 1-100 characters")
        val phone = req.phone?.trim()?.takeIf { it.isNotEmpty() }
        if (phone != null && !PHONE.matches(phone)) throw ValidationException("Invalid phone number")

        val hash = hasher.hash(req.password)
        return db.transaction { conn ->
            val user = try {
                users.create(username, hash, Role.CUSTOMER, displayName, phone)
            } catch (e: SQLIntegrityConstraintViolationException) {
                throw ConflictException("Username is already taken")
            }
            startSession(conn, user)
        }
    }

    fun login(req: LoginRequest): AuthResponse {
        val username = req.username.trim().lowercase()
        val user = db.transaction { users.findByUsername(username) }
        val valid = hasher.verify(req.password, user?.passwordHash ?: dummyHash)
        if (user == null || !valid || !user.active) throw UnauthorizedException("Invalid username or password")
        return db.transaction { startSession(it, user) }
    }

    fun refresh(refreshToken: String): AuthResponse {
        val oldHash = tokens.hashRefreshToken(refreshToken)
        return db.transaction { conn ->
            val now = clock.instant()
            val session = sessions.findActiveByRefreshHash(conn, oldHash, now) ?: throw UnauthorizedException("Session expired")
            val user = users.findById(session.userId)?.takeIf { it.active }
                ?: run {
                    sessions.revoke(conn, session.id, now)
                    throw UnauthorizedException("Session expired")
                }
            val newRefresh = tokens.newRefreshToken()
            if (!sessions.rotate(conn, session.id, oldHash, tokens.hashRefreshToken(newRefresh), tokens.refreshExpiry(now))) {
                throw UnauthorizedException("Session expired")
            }
            response(user, session.id, newRefresh, now)
        }
    }

    fun logout(refreshToken: String) {
        db.transaction { sessions.revokeByRefreshHash(it, tokens.hashRefreshToken(refreshToken), clock.instant()) }
    }

    fun logoutSession(sessionId: Long) {
        db.transaction { sessions.revoke(it, sessionId, clock.instant()) }
    }

    fun isSessionActive(sessionId: Long): Boolean =
        db.transaction { sessions.isActive(it, sessionId, clock.instant()) }

    fun currentUser(userId: Long): User? = db.transaction { users.findById(userId) }?.takeIf { it.active }

    private fun startSession(conn: java.sql.Connection, user: User): AuthResponse {
        val now = clock.instant()
        val refresh = tokens.newRefreshToken()
        val sessionId = sessions.create(conn, user.id, tokens.hashRefreshToken(refresh), tokens.refreshExpiry(now))
        return response(user, sessionId, refresh, now)
    }

    private fun response(user: User, sessionId: Long, refresh: String, now: java.time.Instant) = AuthResponse(
        accessToken = tokens.createAccessToken(user.id, user.role, sessionId, now),
        refreshToken = refresh,
        expiresInSeconds = tokens.accessTokenTtlSeconds(),
        user = user.toDto(),
    )

    private fun validateUsername(username: String) {
        if (!USERNAME.matches(username)) throw ValidationException("Username must be 3-50 characters: letters, digits, '.', '_' or '-'")
    }

    private fun validatePassword(password: String) {
        if (password.length < 8 || password.length > 72) throw ValidationException("Password must be 8-72 characters")
    }

    private companion object {
        val USERNAME = Regex("^[a-z0-9._-]{3,50}$")
        val PHONE = Regex("^[0-9+ -]{7,20}$")
    }
}
