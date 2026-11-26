package dev.livin.nammaoorufood.owner.data

import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.network.apiCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.http.path
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

const val MAX_IMAGE_UPLOAD_BYTES = 2 * 1024 * 1024

enum class ImageUploadFolder(val path: String) {
    HOTEL("namma-ooru-food/hotels"),
    MENU_ITEM("namma-ooru-food/menu-items"),
}

@Serializable
private data class UploadAuth(val token: String, val signature: String, val expire: Long, val publicKey: String)

@Serializable
private data class UploadedImage(val url: String)

interface ImageKitUploadRepository {
    suspend fun upload(bytes: ByteArray, contentType: ContentType, folder: ImageUploadFolder): ApiResult<String>
}

class KtorImageKitUploadRepository(
    private val authenticatedClient: HttpClient,
    private val uploadClient: HttpClient,
    private val publicKey: String,
    private val urlEndpoint: String,
) : ImageKitUploadRepository {
    override suspend fun upload(
        bytes: ByteArray,
        contentType: ContentType,
        folder: ImageUploadFolder,
    ): ApiResult<String> {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_UPLOAD_BYTES) {
            return ApiResult.Rejected(0, "Image must be no larger than 2 MB", false)
        }
        val endpoint = try {
            Url(urlEndpoint)
        } catch (_: IllegalArgumentException) {
            return ApiResult.Rejected(0, "Configure IMAGEKIT_URL_ENDPOINT in local.properties.", false)
        }
        if (publicKey.isBlank() || urlEndpoint.isBlank() || endpoint.protocol != URLProtocol.HTTPS ||
            endpoint.host.isBlank() || !endpoint.parameters.isEmpty()
        ) {
            return ApiResult.Rejected(0, "Configure IMAGEKIT_PUBLIC_KEY and IMAGEKIT_URL_ENDPOINT in local.properties.", false)
        }
        val extension = when (contentType) {
            ContentType.Image.JPEG -> "jpg"
            ContentType.Image.PNG -> "png"
            ContentType("image", "webp") -> "webp"
            else -> return ApiResult.Rejected(0, "Unsupported image format", false)
        }
        val auth = when (val result = authenticatedClient.apiCall<UploadAuth> {
            url.path("api/imagekit/upload-auth")
        }) {
            is ApiResult.Success -> result.value
            is ApiResult.Rejected -> return result.copy(message = "Image upload authentication failed: ${result.message}")
            is ApiResult.NetworkError -> return ApiResult.NetworkError("Image upload authentication failed: ${result.message}")
        }
        if (!Regex("^[a-fA-F0-9-]{36}$").matches(auth.token) || auth.publicKey != publicKey) {
            return ApiResult.Rejected(0, "Invalid image upload authentication response", false)
        }
        return try {
            val fileName = "${auth.token}.$extension"
            val response = uploadClient.post("https://upload.imagekit.io/api/v1/files/upload") {
                setBody(MultiPartFormDataContent(formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, contentType.toString())
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    })
                    append("fileName", fileName)
                    append("folder", folder.path)
                    append("useUniqueFileName", "true")
                    append("publicKey", auth.publicKey)
                    append("token", auth.token)
                    append("expire", auth.expire.toString())
                    append("signature", auth.signature)
                }))
            }
            if (!response.status.isSuccess()) {
                ApiResult.Rejected(response.status.value, "ImageKit upload failed (HTTP ${response.status.value})", false)
            } else {
                val url = response.body<UploadedImage>().url
                val parsed = Url(url)
                val endpointPath = endpoint.encodedPath.trimEnd('/')
                if (parsed.protocol != URLProtocol.HTTPS || parsed.host != endpoint.host ||
                    parsed.port != endpoint.port || !parsed.encodedPath.startsWith("$endpointPath/") || url.length > 2048
                ) {
                    ApiResult.Rejected(0, "ImageKit returned an invalid image URL", false)
                } else {
                    ApiResult.Success(url)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.NetworkError("Image upload failed. Check your connection and try again.")
        }
    }
}
