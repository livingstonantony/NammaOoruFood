package dev.livin.nammaoorufood.user

import kotlinx.serialization.Serializable

enum class Role { CUSTOMER, HOTEL_OWNER, ADMIN }

data class User(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val role: Role,
    val displayName: String,
    val active: Boolean,
)

@Serializable
data class UserDto(val id: Long, val username: String, val displayName: String, val role: Role)

fun User.toDto() = UserDto(id, username, displayName, role)
