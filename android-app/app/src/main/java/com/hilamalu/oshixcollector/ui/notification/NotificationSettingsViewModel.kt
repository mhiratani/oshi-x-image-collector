package com.hilamalu.oshixcollector.ui.notification

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.MediaRepository
import com.hilamalu.oshixcollector.data.notification.NotificationRecommender
import com.hilamalu.oshixcollector.data.notification.NotificationScheduler
import com.hilamalu.oshixcollector.data.notification.NotificationSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 通知対象として選べるアカウント。xUserId未解決のアカウントは画像を持たないため除外する。 */
data class NotifiableAccount(val screenName: String, val xUserId: String)

class NotificationSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = NotificationSettings(application)
    private val repository = MediaRepository(application)
    private val recommender = NotificationRecommender(application)

    val isEnabled: StateFlow<Boolean> = settings.isEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val perDay: StateFlow<Int> = settings.perDay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettings.DEFAULT_PER_DAY)

    val startHour: StateFlow<Int> = settings.startHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettings.DEFAULT_START_HOUR)

    val endHour: StateFlow<Int> = settings.endHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettings.DEFAULT_END_HOUR)

    val targetUserIds: StateFlow<Set<String>> = settings.targetUserIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val favoritesOnly: StateFlow<Boolean> = settings.favoritesOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val accounts: StateFlow<List<NotifiableAccount>> = repository.accounts
        .map { accounts ->
            accounts.mapNotNull { account ->
                account.xUserId?.let { NotifiableAccount(account.screenName, it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** テスト通知の結果メッセージ（スナックバー表示用）。 */
    var message by mutableStateOf<String?>(null)
        private set

    fun setEnabled(enabled: Boolean) = updateSettings { settings.setEnabled(enabled) }

    fun setPerDay(perDay: Int) = updateSettings { settings.setPerDay(perDay) }

    /** 開始時刻。終了時刻を追い越す場合は終了時刻を1時間後ろへずらす。 */
    fun setStartHour(hour: Int) = updateSettings {
        val end = maxOf(endHour.value, hour + 1)
        settings.setHours(hour, end)
    }

    /** 終了時刻。開始時刻を下回る場合は開始時刻を1時間前へずらす。 */
    fun setEndHour(hour: Int) = updateSettings {
        val start = minOf(startHour.value, hour - 1)
        settings.setHours(start, hour)
    }

    fun toggleAccount(xUserId: String, selected: Boolean) = updateSettings {
        val current = targetUserIds.value
        settings.setTargetUserIds(if (selected) current + xUserId else current - xUserId)
    }

    fun setFavoritesOnly(favoritesOnly: Boolean) = updateSettings {
        settings.setFavoritesOnly(favoritesOnly)
    }

    /** 設定画面の「今すぐテスト通知」。予約とは無関係にその場で1枚抽選して通知する。 */
    fun sendTestNotification() {
        viewModelScope.launch {
            val result = recommender.notifyRandom(settings.snapshot())
            message = getApplication<Application>().getString(
                when (result) {
                    NotificationRecommender.Result.NOTIFIED -> R.string.notification_test_notified
                    NotificationRecommender.Result.NO_CANDIDATE -> R.string.notification_test_no_candidate
                    NotificationRecommender.Result.NOT_PERMITTED -> R.string.notification_test_not_permitted
                }
            )
        }
    }

    fun dismissMessage() {
        message = null
    }

    /**
     * 設定を保存し、そのたびに予約を張り直す。頻度・時間帯を変えた直後から新しい設定で通知されるようにする
     * （予約済みの1件を置き換えるだけなので、変更のたびに呼んでも負荷にならない）。
     */
    private fun updateSettings(update: suspend () -> Unit) {
        viewModelScope.launch {
            update()
            NotificationScheduler.sync(getApplication<Application>())
        }
    }
}
