package dev.livin.nammaoorufood

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.livin.nammaoorufood.auth.DataStoreSessionStore
import dev.livin.nammaoorufood.auth.StoredSession
import dev.livin.nammaoorufood.network.Role
import dev.livin.nammaoorufood.network.UserDto
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataStoreSessionStoreTest {
    private val session = StoredSession("super-secret-refresh", UserDto(1, "u", "U", Role.ADMIN))

    @Test
    fun roundTripsSaveLoadAndClear() = runTest {
        val dir = Files.createTempDirectory("datastore-session-test")
        val file = dir.resolve("test_session.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.createWithPath(
            produceFile = { file.toString().toPath() }
        )
        val store = DataStoreSessionStore(dataStore)

        assertNull(store.load())
        store.save(session)
        assertEquals(session, store.load())
        store.clear()
        assertNull(store.load())

        dir.toFile().deleteRecursively()
    }
}
