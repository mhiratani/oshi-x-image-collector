package com.hilamalu.oshixcollector.data.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cloudBackupDataStore by preferencesDataStore(name = "cloud_backup_settings")

/**
 * 「クラウドバックアップ」設定の永続化。
 *
 * 「使いたい」という意思（[isRequested]）と「実際にミラーしてよい状態か」（[isEnabled]）を分けて持つ。
 * 設定画面のマスタートグルは前者で、Firebase未設定・未サインインのままでもONにできる（ONにして初めて
 * R2/Firebaseの入力欄が現れるため）。後者は必要な設定が揃った時にだけtrueになり、
 * MediaRepositoryはこちらだけを見る（未サインインでミラーを試みてお気に入り操作が失敗するのを防ぐ）。
 */
class CloudBackupSettings(private val context: Context) {
    /** 実際にFirestore/R2へミラーしてよいか。設定が揃った時にだけ設定画面からtrueにされる。 */
    val isEnabled: Flow<Boolean> = context.cloudBackupDataStore.data
        .map { prefs -> prefs[KEY_ENABLED] ?: false }

    /**
     * ユーザーがクラウドバックアップを使う意思。設定画面のマスタートグルの状態そのもので、
     * 設定が未完了でもtrueになりうる。
     * 既定値は[isEnabled]（本フラグ導入前からONだった既存ユーザーをOFFに見せないため）。
     */
    val isRequested: Flow<Boolean> = context.cloudBackupDataStore.data
        .map { prefs -> prefs[KEY_REQUESTED] ?: prefs[KEY_ENABLED] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        context.cloudBackupDataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    suspend fun setRequested(requested: Boolean) {
        context.cloudBackupDataStore.edit { prefs -> prefs[KEY_REQUESTED] = requested }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("cloud_backup_enabled")
        val KEY_REQUESTED = booleanPreferencesKey("cloud_backup_requested")
    }
}
