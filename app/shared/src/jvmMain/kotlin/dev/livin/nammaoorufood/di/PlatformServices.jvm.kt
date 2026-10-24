package dev.livin.nammaoorufood.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.livin.nammaoorufood.auth.SessionStore
import dev.livin.nammaoorufood.auth.StoredSession
import dev.livin.nammaoorufood.network.ApiJson
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop has no portable OS keychain, so the session is AES-GCM encrypted with a random key kept
 * in a separate owner-only (0600) file. This blocks casual reads and backups, not a local attacker
 * running as the same user.
 */
class EncryptedFileSessionStore(
    private val dir: Path = Path.of(System.getProperty("user.home"), ".namma-ooru-food", System.getProperty("namma.variant") ?: "default"),
) : SessionStore {
    private val sessionFile = dir.resolve("session.enc")
    private val keyFile = dir.resolve("session.key")

    override suspend fun load(): StoredSession? = withContext(Dispatchers.IO) {
        if (!Files.exists(sessionFile) || !Files.exists(keyFile)) return@withContext null
        val blob = Files.readAllBytes(sessionFile)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, IV_BYTES)))
        val json = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES).decodeToString()
        ApiJson.decodeFromString(StoredSession.serializer(), json)
    }

    override suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(ApiJson.encodeToString(StoredSession.serializer(), session).encodeToByteArray())
        writePrivate(sessionFile, iv + encrypted)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        Files.deleteIfExists(sessionFile)
        Files.deleteIfExists(keyFile)
        Unit
    }

    private fun key(): SecretKeySpec {
        val bytes = if (Files.exists(keyFile)) Files.readAllBytes(keyFile)
        else ByteArray(32).also { SecureRandom().nextBytes(it); writePrivate(keyFile, it) }
        return SecretKeySpec(bytes, "AES")
    }

    private fun writePrivate(target: Path, data: ByteArray) {
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "tmp", ".part")
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
        Files.write(tmp, data)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object { const val IV_BYTES = 12 }
}

private class JvmPlatformServices : PlatformServices {
    override val sessionStore: SessionStore = EncryptedFileSessionStore()
    override val engine: HttpClientEngine = CIO.create()
    override val baseUrl = System.getenv("NAMMA_API_URL") ?: "http://localhost:8080"
}

@Composable
actual fun rememberPlatformServices(): PlatformServices = remember { JvmPlatformServices() }
