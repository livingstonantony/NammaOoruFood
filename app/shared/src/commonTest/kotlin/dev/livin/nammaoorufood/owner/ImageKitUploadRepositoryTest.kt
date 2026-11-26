package dev.livin.nammaoorufood.owner

import dev.livin.nammaoorufood.auth.ApiResult
import dev.livin.nammaoorufood.owner.data.ImageUploadFolder
import dev.livin.nammaoorufood.owner.data.KtorImageKitUploadRepository
import dev.livin.nammaoorufood.owner.data.MAX_IMAGE_UPLOAD_BYTES
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ImageKitUploadRepositoryTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val authJson = """{"token":"01234567-89ab-4cde-8f01-234567890123","expire":1795630000,"signature":"signature","publicKey":"public_test"}"""

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { url("https://api.example.test/") }
    }

    @Test
    fun sendsUploadWithoutOwnerAuthorization() = runTest {
        val authClient = client(MockEngine { request ->
            assertEquals("/api/imagekit/upload-auth", request.url.encodedPath)
            respond(authJson, HttpStatusCode.OK, jsonHeaders)
        })
        val uploadClient = client(MockEngine { request ->
            assertEquals("upload.imagekit.io", request.url.host)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"url":"https://ik.imagekit.io/test/hotel.jpg"}""", HttpStatusCode.OK, jsonHeaders)
        })
        try {
            val result = KtorImageKitUploadRepository(authClient, uploadClient, "public_test", "https://ik.imagekit.io/test")
                .upload(byteArrayOf(1), ContentType.Image.JPEG, ImageUploadFolder.HOTEL)
            assertEquals("https://ik.imagekit.io/test/hotel.jpg", assertIs<ApiResult.Success<String>>(result).value)
        } finally {
            authClient.close()
            uploadClient.close()
        }
    }

    @Test
    fun exactLimitAllowedAndOversizedRejectedBeforeAuthentication() = runTest {
        var authCalls = 0
        val authClient = client(MockEngine {
            authCalls++
            respond(authJson, HttpStatusCode.OK, jsonHeaders)
        })
        val uploadClient = client(MockEngine {
            respond("""{"url":"https://ik.imagekit.io/test/item.jpg"}""", HttpStatusCode.OK, jsonHeaders)
        })
        try {
            val repo = KtorImageKitUploadRepository(authClient, uploadClient, "public_test", "https://ik.imagekit.io/test")
            assertIs<ApiResult.Success<String>>(repo.upload(ByteArray(MAX_IMAGE_UPLOAD_BYTES), ContentType.Image.JPEG, ImageUploadFolder.MENU_ITEM))
            assertIs<ApiResult.Rejected>(repo.upload(ByteArray(MAX_IMAGE_UPLOAD_BYTES + 1), ContentType.Image.JPEG, ImageUploadFolder.MENU_ITEM))
            assertEquals(1, authCalls)
        } finally {
            authClient.close()
            uploadClient.close()
        }
    }

    @Test
    fun authenticationFailureDoesNotUpload() = runTest {
        var uploads = 0
        val authClient = client(MockEngine { respond("""{"message":"Denied"}""", HttpStatusCode.Forbidden, jsonHeaders) })
        val uploadClient = client(MockEngine {
            uploads++
            respond("", HttpStatusCode.InternalServerError)
        })
        try {
            val result = KtorImageKitUploadRepository(authClient, uploadClient, "public_test", "https://ik.imagekit.io/test")
                .upload(byteArrayOf(1), ContentType.Image.PNG, ImageUploadFolder.HOTEL)
            assertEquals(403, assertIs<ApiResult.Rejected>(result).status)
            assertEquals(0, uploads)
        } finally {
            authClient.close()
            uploadClient.close()
        }
    }

    @Test
    fun rejectsMismatchedPublicKeyAndUnexpectedImageHost() = runTest {
        var uploads = 0
        val authClient = client(MockEngine { respond(authJson, HttpStatusCode.OK, jsonHeaders) })
        val uploadClient = client(MockEngine {
            uploads++
            respond("""{"url":"https://other.example/test/item.jpg"}""", HttpStatusCode.OK, jsonHeaders)
        })
        try {
            val mismatched = KtorImageKitUploadRepository(authClient, uploadClient, "wrong", "https://ik.imagekit.io/test")
            assertIs<ApiResult.Rejected>(mismatched.upload(byteArrayOf(1), ContentType.Image.PNG, ImageUploadFolder.HOTEL))
            assertEquals(0, uploads)
            val repo = KtorImageKitUploadRepository(authClient, uploadClient, "public_test", "https://ik.imagekit.io/test")
            assertIs<ApiResult.Rejected>(repo.upload(byteArrayOf(1), ContentType.Image.PNG, ImageUploadFolder.HOTEL))
            assertEquals(1, uploads)
        } finally {
            authClient.close()
            uploadClient.close()
        }
    }
}
