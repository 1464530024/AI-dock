package com.aidock.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aidock.app.AssistantCatalog
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aidock_settings")

/**
 * 轻量偏好存储：当前助手 ID + 各助手的「桌面版 UA」开关。
 * 用 DataStore（协程 + 事务）而非 SharedPreferences，避免主线程 IO。
 */
class Prefs(private val context: Context) {

    private val keyCurrent = stringPreferencesKey("current_assistant_id")
    private val keyDesktop = stringSetPreferencesKey("desktop_mode_ids")

    suspend fun currentId(): String {
        val stored = context.dataStore.data.first()[keyCurrent]
        return if (stored != null && AssistantCatalog.all.any { it.id == stored }) {
            stored
        } else {
            AssistantCatalog.all.first().id
        }
    }

    suspend fun setCurrentId(id: String) {
        context.dataStore.edit { it[keyCurrent] = id }
    }

    suspend fun desktopIds(): Set<String> =
        context.dataStore.data.first()[keyDesktop] ?: emptySet()

    suspend fun setDesktopIds(ids: Set<String>) {
        context.dataStore.edit { it[keyDesktop] = ids }
    }
}
