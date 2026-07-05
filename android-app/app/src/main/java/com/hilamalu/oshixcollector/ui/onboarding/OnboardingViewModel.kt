package com.hilamalu.oshixcollector.ui.onboarding

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilamalu.oshixcollector.data.MediaRepository
import com.hilamalu.oshixcollector.data.backup.CloudBackupSettings
import com.hilamalu.oshixcollector.data.backup.GoogleAuthManager
import com.hilamalu.oshixcollector.data.settings.SecureSettings
import kotlinx.coroutines.launch

sealed interface OnboardingStep {
    /** ローカルDBが空かどうかの初回チェック中。何も描画しない。 */
    data object Checking : OnboardingStep
    data object Welcome : OnboardingStep
    data object AskRestore : OnboardingStep
    data class Restoring(val progress: MediaRepository.RestoreProgress) : OnboardingStep
    data object AskCloudBackup : OnboardingStep
    data object EnablingBackup : OnboardingStep
    data class Error(val message: String, val retryStep: OnboardingStep) : OnboardingStep
    /** 判定完了（既にデータがあった）またはフロー完了。アプリ本体へ遷移する。 */
    data object Done : OnboardingStep
}

/**
 * 初回起動オンボーディング。ローカルDBが空の間だけ表示され、
 * 「クラウドから復元」or「クラウドバックアップを利用」or「ローカルのみ」を選ばせる。
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val secureSettings = SecureSettings(application)
    private val googleAuthManager = GoogleAuthManager(application)
    private val cloudBackupSettings = CloudBackupSettings(application)
    private val repository = MediaRepository(application)

    var step by mutableStateOf<OnboardingStep>(OnboardingStep.Checking)
        private set

    init {
        viewModelScope.launch {
            step = if (repository.hasAnyAccounts()) OnboardingStep.Done else OnboardingStep.Welcome
        }
    }

    fun start() {
        // Firebase未設定なら復元/バックアップともに使えないため、質問をスキップしてそのままアプリへ
        step = if (secureSettings.isFirebaseConfigured) OnboardingStep.AskRestore else OnboardingStep.Done
    }

    fun answerRestore(wantsRestore: Boolean) {
        if (!wantsRestore) {
            step = OnboardingStep.AskCloudBackup
            return
        }
        viewModelScope.launch {
            step = OnboardingStep.Restoring(MediaRepository.RestoreProgress.FetchingMetadata)
            try {
                if (googleAuthManager.currentUser == null) googleAuthManager.signIn()
                repository.restoreFromCloud { progress -> step = OnboardingStep.Restoring(progress) }
                step = OnboardingStep.Done
            } catch (e: Exception) {
                step = OnboardingStep.Error(e.message ?: "復元に失敗しました", OnboardingStep.AskRestore)
            }
        }
    }

    fun answerCloudBackup(wantsBackup: Boolean) {
        if (!wantsBackup) {
            step = OnboardingStep.Done
            return
        }
        viewModelScope.launch {
            step = OnboardingStep.EnablingBackup
            try {
                googleAuthManager.signIn()
                cloudBackupSettings.setEnabled(true)
                step = OnboardingStep.Done
            } catch (e: Exception) {
                step = OnboardingStep.Error(e.message ?: "サインインに失敗しました", OnboardingStep.AskCloudBackup)
            }
        }
    }

    fun retry(retryStep: OnboardingStep) {
        step = retryStep
    }

    fun skip() {
        step = OnboardingStep.Done
    }
}
