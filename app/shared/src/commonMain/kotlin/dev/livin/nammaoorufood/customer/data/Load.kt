package dev.livin.nammaoorufood.customer.data

import dev.livin.nammaoorufood.auth.ApiResult

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Success<T>(val data: T) : Load<T>
    data class Error(val message: String) : Load<Nothing>
}

fun <T> ApiResult<T>.errorMessage(): String = when (this) {
    is ApiResult.Rejected -> message
    is ApiResult.NetworkError -> "Cannot reach the server. Check your connection."
    is ApiResult.Success -> ""
}

fun <T> ApiResult<T>.toLoad(): Load<T> = when (this) {
    is ApiResult.Success -> Load.Success(value)
    else -> Load.Error(errorMessage())
}
