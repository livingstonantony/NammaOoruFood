package dev.livin.nammaoorufood.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.livin.nammaoorufood.network.ApiJson
import kotlinx.coroutines.flow.first

class DataStoreSessionStore(
    private val dataStore: DataStore<Preferences>
) : SessionStore {
    private companion object {
        val KEY_SESSION = stringPreferencesKey("session_json")
    }

    override suspend fun load(): StoredSession? {
        val json = dataStore.data.first()[KEY_SESSION] ?: return null
        return runCatching {
            ApiJson.decodeFromString(StoredSession.serializer(), json)
        }.getOrNull()
    }

    override suspend fun save(session: StoredSession) {
        val json = ApiJson.encodeToString(StoredSession.serializer(), session)
        dataStore.edit { prefs ->
            prefs[KEY_SESSION] = json
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SESSION)
        }
    }
}
