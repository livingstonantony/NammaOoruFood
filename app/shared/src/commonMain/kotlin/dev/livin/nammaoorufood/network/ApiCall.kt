package dev.livin.nammaoorufood.network

import dev.livin.nammaoorufood.auth.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

suspend inline fun <reified R> HttpClient.apiCall(block: HttpRequestBuilder.() -> Unit): ApiResult<R> = try {
    val response = request { block() }
    if (response.status.isSuccess()) {
        @Suppress("UNCHECKED_CAST")
        ApiResult.Success(if (R::class == Unit::class) Unit as R else response.body<R>())
    } else {
        val message = runCatching { response.body<ErrorResponse>().message }.getOrDefault("Request failed")
        ApiResult.Rejected(response.status.value, message, response.status.value == 401 || response.status.value == 403)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ApiResult.NetworkError(e.message ?: "Network error")
}
