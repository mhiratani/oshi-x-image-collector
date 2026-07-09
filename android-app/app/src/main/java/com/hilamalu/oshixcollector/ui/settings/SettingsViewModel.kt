package com.hilamalu.oshixcollector.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilamalu.oshixcollector.data.MediaRepository
import com.hilamalu.oshixcollector.data.backup.CloudBackupSettings
import com.hilamalu.oshixcollector.data.backup.FirebaseAppProvider
import com.hilamalu.oshixcollector.data.backup.GoogleAuthManager
import com.hilamalu.oshixcollector.data.settings.SecureSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RestoreUiState {
    data object Idle : RestoreUiState
    data class InProgress(val progress: MediaRepository.RestoreProgress) : RestoreUiState
    data class Success(
        val result: MediaRepository.RestoreResult,
        /** ローカルが空の状態から実行した初回復元か（表示文言を「復元完了」/「同期完了」で出し分ける）。 */
        val isInitialRestore: Boolean
    ) : RestoreUiState
    data class Failed(val message: String) : RestoreUiState
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val secureSettings = SecureSettings(application)
    private val cloudBackupSettings = CloudBackupSettings(application)
    private val googleAuthManager = GoogleAuthManager(application)
    private val repository = MediaRepository(application)

    val cloudBackupEnabled: StateFlow<Boolean> = cloudBackupSettings.isEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * ローカルにデータがあるか。復元/同期ボタンのラベル出し分けに使う
     * （空=初回なので「クラウドから復元」、データあり=「クラウドと同期」）。
     * 初期値trueにして、既存ユーザーに一瞬「復元」ラベルが見えるのを避ける。
     */
    val hasLocalData: StateFlow<Boolean> = repository.accounts
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    var xBearerToken by mutableStateOf(secureSettings.xBearerToken.orEmpty())
    var r2BucketName by mutableStateOf(secureSettings.r2BucketName.orEmpty())
    var r2AccountId by mutableStateOf(secureSettings.r2AccountId.orEmpty())
    var r2AccessKeyId by mutableStateOf(secureSettings.r2AccessKeyId.orEmpty())
    var r2SecretAccessKey by mutableStateOf(secureSettings.r2SecretAccessKey.orEmpty())
    var r2Endpoint by mutableStateOf(secureSettings.r2Endpoint.orEmpty())

    var firebaseApiKey by mutableStateOf(secureSettings.firebaseApiKey.orEmpty())
    var firebaseProjectId by mutableStateOf(secureSettings.firebaseProjectId.orEmpty())
    var firebaseAppId by mutableStateOf(secureSettings.firebaseAppId.orEmpty())
    var firebaseWebClientId by mutableStateOf(secureSettings.firebaseWebClientId.orEmpty())

    var isFirebaseConfigured by mutableStateOf(secureSettings.isFirebaseConfigured)
        private set

    var signedInEmail by mutableStateOf(googleAuthManager.currentUser?.email)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var restoreState by mutableStateOf<RestoreUiState>(RestoreUiState.Idle)
        private set

    var saved by mutableStateOf(false)
        private set

    fun save() {
        secureSettings.xBearerToken = xBearerToken.ifBlank { null }
        secureSettings.r2BucketName = r2BucketName.ifBlank { null }
        secureSettings.r2AccountId = r2AccountId.ifBlank { null }
        secureSettings.r2AccessKeyId = r2AccessKeyId.ifBlank { null }
        secureSettings.r2SecretAccessKey = r2SecretAccessKey.ifBlank { null }
        secureSettings.r2Endpoint = r2Endpoint.ifBlank { null }

        val firebaseConfigChanged = secureSettings.firebaseApiKey != firebaseApiKey.ifBlank { null } ||
            secureSettings.firebaseProjectId != firebaseProjectId.ifBlank { null } ||
            secureSettings.firebaseAppId != firebaseAppId.ifBlank { null } ||
            secureSettings.firebaseWebClientId != firebaseWebClientId.ifBlank { null }
        if (firebaseConfigChanged) {
            // Firebase(Google)の情報が変わったら既存のサインインは無効なので、
            // サインアウトして「ログインする」ボタンからやり直してもらう。
            // サインアウトは旧FirebaseAppに対して行う必要があるため、値の上書き・リセットより先に実行する。
            googleAuthManager.signOut()
            signedInEmail = null
            viewModelScope.launch { cloudBackupSettings.setEnabled(false) }
        }
        secureSettings.firebaseApiKey = firebaseApiKey.ifBlank { null }
        secureSettings.firebaseProjectId = firebaseProjectId.ifBlank { null }
        secureSettings.firebaseAppId = firebaseAppId.ifBlank { null }
        secureSettings.firebaseWebClientId = firebaseWebClientId.ifBlank { null }
        if (firebaseConfigChanged) {
            // 既存の初期化済みFirebaseAppは古い値を保持し続けるため、次回アクセス時に
            // 新しい値で再初期化されるようリセットする。
            FirebaseAppProvider.reset(getApplication<Application>())
        }
        isFirebaseConfigured = secureSettings.isFirebaseConfigured

        saved = true
    }

    fun dismissSaved() {
        saved = false
    }

    fun dismissError() {
        errorMessage = null
    }

    /** 「ログインする」ボタンから呼ぶ。成功するとトグル表示に切り替わる。 */
    fun signIn() {
        viewModelScope.launch {
            try {
                signedInEmail = googleAuthManager.signIn().email
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun setCloudBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                try {
                    // トグルはログイン後にしか表示されないが、セッション失効時の保険として残す
                    if (googleAuthManager.currentUser == null) {
                        signedInEmail = googleAuthManager.signIn().email
                    }
                    cloudBackupSettings.setEnabled(true)
                    repository.backupExistingIfEnabled()
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            } else {
                cloudBackupSettings.setEnabled(false)
            }
        }
    }

    fun restoreFromCloud() {
        if (restoreState is RestoreUiState.InProgress) return
        viewModelScope.launch {
            val isInitialRestore = !hasLocalData.value
            restoreState = RestoreUiState.InProgress(MediaRepository.RestoreProgress.FetchingMetadata)
            try {
                if (googleAuthManager.currentUser == null) {
                    signedInEmail = googleAuthManager.signIn().email
                }
                val result = repository.restoreFromCloud { progress ->
                    restoreState = RestoreUiState.InProgress(progress)
                }
                restoreState = RestoreUiState.Success(result, isInitialRestore)
            } catch (e: Exception) {
                restoreState = RestoreUiState.Failed(e.message ?: "復元に失敗しました")
            }
        }
    }

    fun dismissRestoreState() {
        restoreState = RestoreUiState.Idle
    }
}
