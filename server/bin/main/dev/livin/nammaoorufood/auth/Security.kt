package dev.livin.nammaoorufood.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.livin.nammaoorufood.config.AuthConfig
import dev.livin.nammaoorufood.user.Role
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date

class PasswordHasher(private val cost: Int = 12) {
    fun hash(password: String): String = BCrypt.withDefaults().hashToString(cost, password.toCharArray())

    fun verify(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}

class TokenService(private val config: AuthConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)
    private val random = SecureRandom()

    val verifier = JWT.require(algorithm).withIssuer(ISSUER).build()

    fun accessTokenTtlSeconds(): Long = config.accessTokenMinutes * 60

    fun createAccessToken(userId: Long, role: Role, sessionId: Long, now: Instant): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withSubject(userId.toString())
            .withClaim(CLAIM_ROLE, role.name)
            .withClaim(CLAIM_SESSION, sessionId)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(accessTokenTtlSeconds())))
            .sign(algorithm)

    fun newRefreshToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun refreshExpiry(now: Instant): Instant = now.plusSeconds(config.refreshTokenDays * 86_400)

    fun hashRefreshToken(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val ISSUER = "namma-ooru-food"
        const val CLAIM_ROLE = "role"
        const val CLAIM_SESSION = "sid"
    }
}
