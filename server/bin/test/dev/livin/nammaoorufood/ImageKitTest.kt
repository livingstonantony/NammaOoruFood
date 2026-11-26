package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.auth.JWT_AUTH
import dev.livin.nammaoorufood.auth.UserPrincipal
import dev.livin.nammaoorufood.imagekit.ImageKitConfig
import dev.livin.nammaoorufood.imagekit.imageKitRoutes
import dev.livin.nammaoorufood.plugins.configureErrorHandling
import dev.livin.nammaoorufood.plugins.configureSerialization
import dev.livin.nammaoorufood.user.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal fun testImageKit() = ImageKitConfig.from(
    MapApplicationConfig(
        "imagekit.publicKey" to "public_test",
        "imagekit.privateKey" to "private_key_test",
        "imagekit.urlEndpoint" to "https://ik.imagekit.io/example",
    ),
)

class ImageKitTest {
    private fun config() = testImageKit()

    @Test
    fun signsAccordingToOfficialSdkTestVector() {
        assertEquals("e71bcd6031016b060d349d212e23e85c791decdd", config().sign("your_token", 1582269249))
    }

    @Test
    fun credentialsAreUniqueAndExpireAfterThirtyMinutes() {
        val clock = Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC)
        val first = config().credentials(clock)
        val second = config().credentials(clock)
        assertEquals(2_800L, first.expire)
        assertEquals("public_test", first.publicKey)
        assertNotEquals(first.token, second.token)
        assertEquals(config().sign(first.token, first.expire), first.signature)
    }

    @Test
    fun configurationRejectsMissingOrUnsafeValues() {
        assertFailsWith<IllegalStateException> { ImageKitConfig.from(MapApplicationConfig()) }
        assertFailsWith<IllegalArgumentException> {
            ImageKitConfig.from(MapApplicationConfig(
                "imagekit.publicKey" to "public_test",
                "imagekit.privateKey" to "private_key_test",
                "imagekit.urlEndpoint" to "http://example.com",
            ))
        }
    }

    @Test
    fun endpointRequiresOwnerAndDoesNotReturnPrivateKey() = testApplication {
        application {
            configureSerialization()
            configureErrorHandling()
            install(Authentication) {
                bearer(JWT_AUTH) {
                    authenticate { credentials ->
                        when (credentials.token) {
                            "owner" -> UserPrincipal(1, Role.HOTEL_OWNER, 1)
                            "customer" -> UserPrincipal(2, Role.CUSTOMER, 2)
                            else -> null
                        }
                    }
                }
            }
            routing { imageKitRoutes(config()) }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/imagekit/upload-auth").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/imagekit/upload-auth") { bearerAuth("customer") }.status)
        val response = client.get("/api/imagekit/upload-auth") { bearerAuth("owner") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.bodyAsText()
        assertTrue(body.contains("\"publicKey\":\"public_test\""))
        assertFalse(body.contains("private_key_test"))
        assertFalse(body.contains("privateKey"))
    }
}
