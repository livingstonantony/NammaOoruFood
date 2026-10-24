package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.network.UserDto

sealed interface AuthState {
    data object Initializing : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val user: UserDto) : AuthState
    data class ReauthenticationRequired(val reason: String) : AuthState
}
