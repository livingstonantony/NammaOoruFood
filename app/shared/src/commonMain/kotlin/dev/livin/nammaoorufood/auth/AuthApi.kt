package dev.livin.nammaoorufood.auth

import dev.livin.nammaoorufood.network.AuthResponse
import dev.livin.nammaoorufood.network.ErrorResponse
import dev.livin.nammaoorufood.network.LoginRequest
import dev.livin.nammaoorufood.network.RefreshRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    /** Server answered with an error; [unauthorized] is true for 401/403. */
    data class Rejected(val status: Int, val message: String, val unauthorized: Boolean) : ApiResult<Nothing>
    data class NetworkError(val message: String) : ApiResult<Nothing>
}

interface AuthApi {
    suspend fun login(username: String, password: String): ApiResult<AuthResponse>
    suspend fun refresh(refreshToken: String): ApiResult<AuthResponse>
    suspend fun logout(refreshToken: String): ApiResult<Unit>
}

/** Uses a client WITHOUT the bearer plugin so refresh calls can never recurse. */
class KtorAuthApi(private val client: HttpClient) : AuthApi {
    override suspend fun login(username: String, password: String) =
        call<LoginRequest, AuthResponse>("/auth/login", LoginRequest(username, password))

    override suspend fun refresh(refreshToken: String) =
        call<RefreshRequest, AuthResponse>("/auth/refresh", RefreshRequest(refreshToken))

    override suspend fun logout(refreshToken: String) =
        call<RefreshRequest, Unit>("/auth/logout", RefreshRequest(refreshToken))

    private suspend inline fun <reified B, reified R> call(path: String, payload: B): ApiResult<R> = try {
        val response = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        when {
            response.status.isSuccess() ->
                @Suppress("UNCHECKED_CAST")
                ApiResult.Success(if (R::class == Unit::class) Unit as R else response.body<R>())
            else -> {
                val message = runCatching { response.body<ErrorResponse>().message }.getOrDefault("Request failed")
                ApiResult.Rejected(response.status.value, message, response.status.value in listOf(401, 403))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.NetworkError(e.message ?: "Network error")
    }
}
