package dev.livin.nammaoorufood.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.livin.nammaoorufood.auth.DataStoreSessionStore
import dev.livin.nammaoorufood.auth.SessionStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSBundle

@OptIn(ExperimentalForeignApi::class)
fun createIosDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = false,
                error = null
            )
            (requireNotNull(documentDirectory).path + "/namma_session.preferences_pb").toPath()
        }
    )

private class IosPlatformServices : PlatformServices {
    override val sessionStore: SessionStore = DataStoreSessionStore(createIosDataStore())
    override val engine: HttpClientEngine = Darwin.create()
    // Overridable with a `NammaApiUrl` key in the app Info.plist.
    override val baseUrl = (NSBundle.mainBundle.objectForInfoDictionaryKey("NammaApiUrl") as? String)
        ?.takeIf { it.isNotBlank() } ?: "http://localhost:8080"
}

@Composable
actual fun rememberPlatformServices(): PlatformServices = remember { IosPlatformServices() }
