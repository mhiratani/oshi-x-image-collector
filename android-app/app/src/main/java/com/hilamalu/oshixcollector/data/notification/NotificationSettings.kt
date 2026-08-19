package com.hilamalu.oshixcollector.data.notification

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notificationDataStore by preferencesDataStore(name = "notification_settings")

/** 通知回数の上限。多すぎると通知が鬱陶しくなるうえ、スロット幅が短くなり過ぎる。 */
const val MAX_NOTIFICATIONS_PER_DAY = 12

/**
 * 「おすすめ通知」設定の永続化（[com.hilamalu.oshixcollector.data.backup.CloudBackupSettings]と同じDataStore方式）。
 *
 * 通知の抽選対象・頻度・時間帯だけを持つ。実際のスケジューリングは
 * [NotificationScheduler]、抽選と表示は[OshiNotificationWorker]が行う。
 */
class NotificationSettings(private val context: Context) {

    /** ワーカーから1回の実行で使う設定のスナップショット。個別のFlowを何度も読まずに済ませるためのまとめ。 */
    data class Snapshot(
        val enabled: Boolean,
        val perDay: Int,
        val startHour: Int,
        val endHour: Int,
        /** 空なら追跡中の全アカウントが対象。 */
        val targetUserIds: Set<String>,
        val favoritesOnly: Boolean,
        val faceOnly: Boolean
    )

    val isEnabled: Flow<Boolean> = context.notificationDataStore.data
        .map { prefs -> prefs[KEY_ENABLED] ?: false }

    /** 1日あたりの通知回数（1〜[MAX_NOTIFICATIONS_PER_DAY]）。 */
    val perDay: Flow<Int> = context.notificationDataStore.data
        .map { prefs -> (prefs[KEY_PER_DAY] ?: DEFAULT_PER_DAY).coerceIn(1, MAX_NOTIFICATIONS_PER_DAY) }

    /** 通知してよい時間帯の開始時刻（0〜23）。 */
    val startHour: Flow<Int> = context.notificationDataStore.data
        .map { prefs -> (prefs[KEY_START_HOUR] ?: DEFAULT_START_HOUR).coerceIn(0, 23) }

    /** 通知してよい時間帯の終了時刻（1〜24）。[startHour]以下の場合は終日として扱う。 */
    val endHour: Flow<Int> = context.notificationDataStore.data
        .map { prefs -> (prefs[KEY_END_HOUR] ?: DEFAULT_END_HOUR).coerceIn(1, 24) }

    /** 通知対象に選んだアカウントのxUserId。空＝すべての追跡アカウントが対象。 */
    val targetUserIds: Flow<Set<String>> = context.notificationDataStore.data
        .map { prefs -> prefs[KEY_TARGET_USER_IDS] ?: emptySet() }

    /** trueならお気に入り登録済みの画像だけから抽選する。 */
    val favoritesOnly: Flow<Boolean> = context.notificationDataStore.data
        .map { prefs -> prefs[KEY_FAVORITES_ONLY] ?: false }

    /** trueなら顔ありと判定された画像だけから抽選する。[favoritesOnly]と併用するとAND条件になる。 */
    val faceOnly: Flow<Boolean> = context.notificationDataStore.data
        .map { prefs -> prefs[KEY_FACE_ONLY] ?: false }

    suspend fun snapshot(): Snapshot {
        val prefs = context.notificationDataStore.data.first()
        return Snapshot(
            enabled = prefs[KEY_ENABLED] ?: false,
            perDay = (prefs[KEY_PER_DAY] ?: DEFAULT_PER_DAY).coerceIn(1, MAX_NOTIFICATIONS_PER_DAY),
            startHour = (prefs[KEY_START_HOUR] ?: DEFAULT_START_HOUR).coerceIn(0, 23),
            endHour = (prefs[KEY_END_HOUR] ?: DEFAULT_END_HOUR).coerceIn(1, 24),
            targetUserIds = prefs[KEY_TARGET_USER_IDS] ?: emptySet(),
            favoritesOnly = prefs[KEY_FAVORITES_ONLY] ?: false,
            faceOnly = prefs[KEY_FACE_ONLY] ?: false
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    suspend fun setPerDay(perDay: Int) {
        context.notificationDataStore.edit { prefs ->
            prefs[KEY_PER_DAY] = perDay.coerceIn(1, MAX_NOTIFICATIONS_PER_DAY)
        }
    }

    /** 開始 >= 終了になる指定は受け付けない（呼び出し側のUIで整合を取る）。 */
    suspend fun setHours(startHour: Int, endHour: Int) {
        context.notificationDataStore.edit { prefs ->
            prefs[KEY_START_HOUR] = startHour.coerceIn(0, 23)
            prefs[KEY_END_HOUR] = endHour.coerceIn(1, 24)
        }
    }

    suspend fun setTargetUserIds(userIds: Set<String>) {
        context.notificationDataStore.edit { prefs -> prefs[KEY_TARGET_USER_IDS] = userIds }
    }

    suspend fun setFavoritesOnly(favoritesOnly: Boolean) {
        context.notificationDataStore.edit { prefs -> prefs[KEY_FAVORITES_ONLY] = favoritesOnly }
    }

    suspend fun setFaceOnly(faceOnly: Boolean) {
        context.notificationDataStore.edit { prefs -> prefs[KEY_FACE_ONLY] = faceOnly }
    }

    companion object {
        /** 既定値。設定を読み込む前のUIの初期表示にも使う。 */
        const val DEFAULT_PER_DAY = 3
        const val DEFAULT_START_HOUR = 9
        const val DEFAULT_END_HOUR = 22

        private val KEY_ENABLED = booleanPreferencesKey("notification_enabled")
        private val KEY_PER_DAY = intPreferencesKey("notification_per_day")
        private val KEY_START_HOUR = intPreferencesKey("notification_start_hour")
        private val KEY_END_HOUR = intPreferencesKey("notification_end_hour")
        private val KEY_TARGET_USER_IDS = stringSetPreferencesKey("notification_target_user_ids")
        private val KEY_FAVORITES_ONLY = booleanPreferencesKey("notification_favorites_only")
        private val KEY_FACE_ONLY = booleanPreferencesKey("notification_face_only")
    }
}
