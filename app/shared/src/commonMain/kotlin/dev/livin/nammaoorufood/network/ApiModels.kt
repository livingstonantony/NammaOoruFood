package dev.livin.nammaoorufood.network

import kotlinx.serialization.Serializable

@Serializable
enum class Role { CUSTOMER, HOTEL_OWNER, ADMIN }

@Serializable
data class UserDto(val id: Long, val username: String, val displayName: String, val role: Role)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: UserDto,
)

@Serializable
data class ErrorResponse(val code: String, val message: String)
