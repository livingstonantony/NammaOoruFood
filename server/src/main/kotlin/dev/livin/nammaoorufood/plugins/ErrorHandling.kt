package dev.livin.nammaoorufood.plugins

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val code: String, val message: String)

open class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

class ValidationException(message: String) : ApiException(HttpStatusCode.BadRequest, "VALIDATION_ERROR", message)
class NotFoundException(message: String) : ApiException(HttpStatusCode.NotFound, "NOT_FOUND", message)
class ConflictException(message: String) : ApiException(HttpStatusCode.Conflict, "CONFLICT", message)
class UnauthorizedException(message: String = "Authentication required") :
    ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", message)
class ForbiddenException(message: String = "Access denied") :
    ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", message)

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ErrorResponse(e.code, e.message ?: e.code))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Malformed request body"))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "Something went wrong"))
        }
    }
}
