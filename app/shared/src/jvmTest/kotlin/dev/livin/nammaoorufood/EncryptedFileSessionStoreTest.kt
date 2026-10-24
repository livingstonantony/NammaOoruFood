package dev.livin.nammaoorufood

import dev.livin.nammaoorufood.auth.StoredSession
import dev.livin.nammaoorufood.di.EncryptedFileSessionStore
import dev.livin.nammaoorufood.network.Role
import dev.livin.nammaoorufood.network.UserDto
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EncryptedFileSessionStoreTest {
    private val session = StoredSession("super-secret-refresh", UserDto(1, "u", "U", Role.ADMIN))

    @Test
    fun roundTripsEncryptsAtRestAndClears() = runTest {
        val dir = Files.createTempDirectory("session-test")
        val store = EncryptedFileSessionStore(dir)
        assertNull(store.load())
        store.save(session)
        assertEquals(session, EncryptedFileSessionStore(dir).load())
        assertFalse(Files.readAllBytes(dir.resolve("session.enc")).decodeToString().contains("super-secret"))
        store.clear()
        assertNull(store.load())
        dir.toFile().deleteRecursively()
    }
}
