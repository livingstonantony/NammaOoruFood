package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.network.UserDto
import kotlinx.serialization.Serializable

/** Only the long-lived refresh token and profile are persisted; access tokens and passwords never are. */
@Serializable
data class StoredSession(val refreshToken: String, val user: UserDto)

interface SessionStore {
    suspend fun load(): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun clear()
}

class InMemorySessionStore : SessionStore {
    private var session: StoredSession? = null
    override suspend fun load() = session
    override suspend fun save(session: StoredSession) { this.session = session }
    override suspend fun clear() { session = null }
}
