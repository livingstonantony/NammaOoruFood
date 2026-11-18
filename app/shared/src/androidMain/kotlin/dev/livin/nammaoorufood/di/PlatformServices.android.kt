package dev.livin.nammaoorufood.di

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.livin.nammaoorufood.auth.DataStoreSessionStore
import dev.livin.nammaoorufood.auth.SessionStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okio.Path.Companion.toPath

fun createAndroidDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        produceFile = { context.applicationContext.filesDir.resolve("namma_session.preferences_pb").absolutePath.toPath() }
    )

private class AndroidPlatformServices(context: Context) : PlatformServices {
    override val sessionStore: SessionStore = DataStoreSessionStore(createAndroidDataStore(context))
    override val engine: HttpClientEngine = OkHttp.create()
    // Set by the app manifest from the `nammaApiUrl` Gradle property; 10.0.2.2 is the host machine from the emulator.
    override val baseUrl: String = run {
        val rawUrl = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                ).metaData?.getString("namma.api.url")
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString("namma.api.url")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "http://10.0.2.2:8080"

        val isEmulator = android.os.Build.FINGERPRINT.startsWith("generic") ||
            android.os.Build.MODEL.contains("google_sdk") ||
            android.os.Build.MODEL.contains("Emulator") ||
            android.os.Build.MODEL.contains("Android SDK built for x86") ||
            android.os.Build.HARDWARE.contains("goldfish") ||
            android.os.Build.HARDWARE.contains("ranchu")

        if (!isEmulator && rawUrl.contains("10.0.2.2")) {
            rawUrl.replace("10.0.2.2", "10.0.2.2")
        } else {
            rawUrl
        }
    }
}

@Composable
actual fun rememberPlatformServices(): PlatformServices {
    val context = LocalContext.current.applicationContext
    return remember { AndroidPlatformServices(context) }
}
