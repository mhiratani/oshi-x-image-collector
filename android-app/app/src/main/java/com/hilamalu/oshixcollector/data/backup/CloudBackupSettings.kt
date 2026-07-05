package com.hilamalu.oshixcollector.data.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cloudBackupDataStore by preferencesDataStore(name = "cloud_backup_settings")

/** 「クラウドバックアップ」ON/OFF設定の永続化。 */
class CloudBackupSettings(private val context: Context) {
    val isEnabled: Flow<Boolean> = context.cloudBackupDataStore.data
        .map { prefs -> prefs[KEY_ENABLED] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        context.cloudBackupDataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("cloud_backup_enabled")
    }
}
