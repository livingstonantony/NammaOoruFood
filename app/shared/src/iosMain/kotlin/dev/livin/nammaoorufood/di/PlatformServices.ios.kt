package dev.livin.nammaoorufood.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.livin.nammaoorufood.auth.SessionStore
import dev.livin.nammaoorufood.auth.StoredSession
import dev.livin.nammaoorufood.network.ApiJson
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

/** Session stored in the iOS Keychain, readable only after first unlock and never synced or backed up. */
@OptIn(ExperimentalForeignApi::class)
class KeychainSessionStore : SessionStore {
    override suspend fun load(): StoredSession? = withQuery(returnData = true) { query ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            when (status) {
                errSecItemNotFound -> null
                errSecSuccess -> {
                    val data = CFBridgingRelease(result.value) as NSData
                    val bytes = data.bytes?.readBytes(data.length.toInt()) ?: ByteArray(0)
                    ApiJson.decodeFromString(StoredSession.serializer(), bytes.decodeToString())
                }
                else -> error("Keychain read failed: $status")
            }
        }
    }

    override suspend fun save(session: StoredSession) {
        clear()
        val bytes = ApiJson.encodeToString(StoredSession.serializer(), session).encodeToByteArray()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        withQuery(returnData = false, value = data) { query ->
            val status = SecItemAdd(query, null)
            check(status == errSecSuccess) { "Keychain write failed: $status" }
        }
    }

    override suspend fun clear() {
        withQuery(returnData = false) { query -> SecItemDelete(query) }
    }

    private fun <T> withQuery(returnData: Boolean, value: NSData? = null, block: (CFDictionaryRef?) -> T): T {
        val dict = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val retained = mutableListOf<CFTypeRef?>()
        val put = { key: CFStringRef?, v: CFTypeRef? -> CFDictionaryAddValue(dict, key, v) }
        val bridge = { o: Any? -> CFBridgingRetain(o).also { retained += it } }
        try {
            put(kSecClass, kSecClassGenericPassword)
            put(kSecAttrService, bridge(SERVICE))
            put(kSecAttrAccount, bridge(ACCOUNT))
            if (returnData) {
                put(kSecReturnData, kCFBooleanTrue)
                put(kSecMatchLimit, kSecMatchLimitOne)
            }
            if (value != null) {
                put(kSecValueData, bridge(value))
                put(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            }
            return block(dict)
        } finally {
            retained.forEach { CFRelease(it) }
            CFRelease(dict)
        }
    }

    private companion object {
        const val SERVICE = "dev.livin.nammaoorufood.session"
        const val ACCOUNT = "current"
    }
}

private class IosPlatformServices : PlatformServices {
    override val sessionStore: SessionStore = KeychainSessionStore()
    override val engine: HttpClientEngine = Darwin.create()
    // Overridable with a `NammaApiUrl` key in the app Info.plist.
    override val baseUrl = (NSBundle.mainBundle.objectForInfoDictionaryKey("NammaApiUrl") as? String)
        ?.takeIf { it.isNotBlank() } ?: "http://localhost:8080"
}

@Composable
actual fun rememberPlatformServices(): PlatformServices = remember { IosPlatformServices() }
