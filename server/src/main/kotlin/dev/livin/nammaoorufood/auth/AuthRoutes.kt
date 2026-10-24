package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.plugins.ForbiddenException
import dev.livin.nammaoorufood.plugins.UnauthorizedException
import dev.livin.nammaoorufood.plugins.ErrorResponse
import dev.livin.nammaoorufood.user.Role
import dev.livin.nammaoorufood.user.UserDto
import dev.livin.nammaoorufood.user.toDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.minutes

data class UserPrincipal(val userId: Long, val role: Role, val sessionId: Long)

const val JWT_AUTH = "jwt-auth"
private val LOGIN_LIMIT = RateLimitName("auth-attempts")

fun Application.configureAuthentication(auth: AuthService, tokens: TokenService, loginLimitPerMinute: Int) {
    install(Authentication) {
        jwt(JWT_AUTH) {
            verifier(tokens.verifier)
            validate { cred ->
                val userId = cred.payload.subject?.toLongOrNull()
                val sid = cred.payload.getClaim(TokenService.CLAIM_SESSION).asLong()
                val role = cred.payload.getClaim(TokenService.CLAIM_ROLE).asString()?.let { runCatching { Role.valueOf(it) }.getOrNull() }
                // Server-side check makes logout/revocation take effect before the access token expires.
                if (userId != null && sid != null && role != null && auth.isSessionActive(sid)) UserPrincipal(userId, role, sid) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Authentication required"))
            }
        }
    }
    install(RateLimit) {
        register(LOGIN_LIMIT) {
            rateLimiter(limit = loginLimitPerMinute, refillPeriod = 1.minutes)
        }
    }
}

fun ApplicationCall.principalOrThrow(): UserPrincipal = principal<UserPrincipal>() ?: throw UnauthorizedException()

fun UserPrincipal.requireRole(vararg allowed: Role) {
    if (role !in allowed) throw ForbiddenException()
}

fun Route.authRoutes(auth: AuthService) {
    route("/auth") {
        rateLimit(LOGIN_LIMIT) {
            post("/register") {
                call.respond(HttpStatusCode.Created, auth.registerCustomer(call.receive<RegisterRequest>()))
            }
            post("/login") {
                call.respond(auth.login(call.receive<LoginRequest>()))
            }
            post("/refresh") {
                call.respond(auth.refresh(call.receive<RefreshRequest>().refreshToken))
            }
        }
        post("/logout") {
            auth.logout(call.receive<RefreshRequest>().refreshToken)
            call.respond(HttpStatusCode.NoContent)
        }
        authenticate(JWT_AUTH) {
            get("/me") {
                val user = auth.currentUser(call.principalOrThrow().userId) ?: throw UnauthorizedException()
                call.respond<UserDto>(user.toDto())
            }
        }
    }
}
