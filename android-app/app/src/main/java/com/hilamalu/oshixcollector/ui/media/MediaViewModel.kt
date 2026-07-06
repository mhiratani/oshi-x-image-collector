package com.hilamalu.oshixcollector.ui.media

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilamalu.oshixcollector.data.MediaRepository
import com.hilamalu.oshixcollector.data.backup.CloudBackupSettings
import com.hilamalu.oshixcollector.data.backup.GoogleAuthManager
import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MediaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository(application)
    private val cloudBackupSettings = CloudBackupSettings(application)
    private val googleAuthManager = GoogleAuthManager(application)

    private val faceOnly = MutableStateFlow(false)

    val media: StateFlow<List<MediaAssetEntity>> =
        combine(repository.media, faceOnly) { assets, faceOnlyEnabled ->
            if (faceOnlyEnabled) assets.filter { it.isFace == true } else assets
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isFaceOnly: StateFlow<Boolean> = faceOnly

    var isRefreshing by mutableStateOf(false)
        private set

    /** トップバーの同期アイコンの実行中状態。「最新を取得」（[isRefreshing]）とは別の操作。 */
    var isSyncing by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var syncMessage by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            errorMessage = null
            try {
                repository.refreshAll()
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isRefreshing = false
            }
        }
    }

    /**
     * トップバーの同期アイコンから呼ぶ。クラウドの最新状態をローカルへ取り込む（pull）。
     * クラウドバックアップ未設定/未サインインの場合はサインインを促す（既存のSettings/Onboardingと同様）。
     */
    fun syncFromCloud() {
        if (isSyncing) return
        viewModelScope.launch {
            isSyncing = true
            errorMessage = null
            try {
                if (!cloudBackupSettings.isEnabled.first()) {
                    errorMessage = "設定画面でクラウドバックアップを有効にしてください"
                    return@launch
                }
                if (googleAuthManager.currentUser == null) {
                    googleAuthManager.signIn()
                }
                val result = repository.restoreFromCloud()
                syncMessage =
                    "同期完了: アカウント${result.accountsRestored}件、メタデータ${result.mediaRowsRestored}件、画像${result.imagesDownloaded}件ダウンロード"
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isSyncing = false
            }
        }
    }

    fun setFaceOnly(enabled: Boolean) {
        faceOnly.value = enabled
    }

    fun dismissError() {
        errorMessage = null
    }

    fun dismissSyncMessage() {
        syncMessage = null
    }
}


