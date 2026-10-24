package dev.livin.nammaoorufood.di

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.livin.nammaoorufood.auth.SessionStore
import dev.livin.nammaoorufood.auth.StoredSession
import dev.livin.nammaoorufood.network.ApiJson
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Session encrypted with a non-exportable AES key held in the Android Keystore. */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("namma_session", Context.MODE_PRIVATE)

    override suspend fun load(): StoredSession? = withContext(Dispatchers.IO) {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return@withContext null
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob.copyOfRange(0, IV_BYTES)))
        val json = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES).decodeToString()
        ApiJson.decodeFromString(StoredSession.serializer(), json)
    }

    override suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(ApiJson.encodeToString(StoredSession.serializer(), session).encodeToByteArray())
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "namma_session_key"
        const val KEY_BLOB = "session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

private class AndroidPlatformServices(context: Context) : PlatformServices {
    override val sessionStore: SessionStore = KeystoreSessionStore(context)
    override val engine: HttpClientEngine = OkHttp.create()
    // Set by the app manifest from the `nammaApiUrl` Gradle property; 10.0.2.2 is the host machine from the emulator.
    override val baseUrl = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("namma.api.url")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "http://10.0.2.2:8080"
}

@Composable
actual fun rememberPlatformServices(): PlatformServices {
    val context = LocalContext.current.applicationContext
    return remember { AndroidPlatformServices(context) }
}
