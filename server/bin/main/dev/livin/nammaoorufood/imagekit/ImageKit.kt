package dev.livin.nammaoorufood.imagekit

import dev.livin.nammaoorufood.auth.JWT_AUTH
import dev.livin.nammaoorufood.auth.principalOrThrow
import dev.livin.nammaoorufood.auth.requireRole
import dev.livin.nammaoorufood.user.Role
import io.ktor.server.auth.authenticate
import io.ktor.server.config.ApplicationConfig
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Clock
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ImageKitConfig private constructor(
    val publicKey: String,
    private val privateKey: String,
    val urlEndpoint: String,
) {
    fun credentials(clock: Clock = Clock.systemUTC()): UploadAuth {
        val token = UUID.randomUUID().toString()
        val expire = clock.instant().epochSecond + 1800
        return UploadAuth(token, sign(token, expire), expire, publicKey)
    }

    internal fun sign(token: String, expire: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(privateKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal("$token$expire".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        fun from(config: ApplicationConfig): ImageKitConfig {
            fun required(name: String): String =
                config.propertyOrNull("imagekit.$name")?.getString()?.takeIf { it.isNotBlank() }
                    ?: error("Missing required ImageKit configuration: imagekit.$name")
            val publicKey = required("publicKey")
            val privateKey = required("privateKey")
            val endpoint = required("urlEndpoint")
            val uri = try {
                URI(endpoint)
            } catch (_: IllegalArgumentException) {
                error("Invalid ImageKit configuration: imagekit.urlEndpoint must be an HTTPS URL")
            }
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null) {
                "Invalid ImageKit configuration: imagekit.urlEndpoint must be an HTTPS URL"
            }
            return ImageKitConfig(publicKey, privateKey, endpoint.trimEnd('/'))
        }
    }
}

@Serializable
data class UploadAuth(val token: String, val signature: String, val expire: Long, val publicKey: String)

fun Route.imageKitRoutes(config: ImageKitConfig) {
    authenticate(JWT_AUTH) {
        get("/api/imagekit/upload-auth") {
            call.principalOrThrow().requireRole(Role.HOTEL_OWNER)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(config.credentials())
        }
    }
}
