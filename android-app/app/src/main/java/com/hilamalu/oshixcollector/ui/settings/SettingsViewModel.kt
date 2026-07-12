package com.hilamalu.oshixcollector.ui.settings

import android.app.Application
import android.net.Uri
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
import com.hilamalu.oshixcollector.data.settings.SettingsTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 設定エクスポート/インポート(機種変更引き継ぎ)の結果表示用。 */
sealed interface TransferUiState {
    data object Idle : TransferUiState
    data object Exported : TransferUiState
    data object Imported : TransferUiState
    data class Failed(val message: String) : TransferUiState
}

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

    /**
     * FirebaseAppProviderが現在参照している（＝前回[applyFirebaseConfigIfChanged]を通過した）
     * Firebase構成のスナップショット。保存済みの値と比べて再初期化の要否を判定する。
     */
    private var appliedFirebaseConfig = storedFirebaseConfig()

    private fun storedFirebaseConfig() = listOf(
        secureSettings.firebaseApiKey,
        secureSettings.firebaseProjectId,
        secureSettings.firebaseAppId,
        secureSettings.firebaseWebClientId
    )

    /** Bearer Token欄からフォーカスが外れた時に呼ぶ（保存ボタンは無く、入力のたびに自動保存する）。 */
    fun saveXBearerToken() {
        secureSettings.xBearerToken = xBearerToken.ifBlank { null }
    }

    /** R2欄のいずれかからフォーカスが外れた時に呼ぶ（5項目まとめて自動保存）。 */
    fun saveR2Settings() {
        secureSettings.r2BucketName = r2BucketName.ifBlank { null }
        secureSettings.r2AccountId = r2AccountId.ifBlank { null }
        secureSettings.r2AccessKeyId = r2AccessKeyId.ifBlank { null }
        secureSettings.r2SecretAccessKey = r2SecretAccessKey.ifBlank { null }
        secureSettings.r2Endpoint = r2Endpoint.ifBlank { null }
    }

    /**
     * Firebase欄のいずれかからフォーカスが外れた時に呼ぶ（4項目まとめて自動保存）。
     * 1項目ずつの自動保存中に入力途中の構成でサインアウトが走らないよう、ここでは保存のみ行い、
     * サインアウト＋再初期化のカスケードは[applyFirebaseConfigIfChanged]まで遅延する。
     */
    fun saveFirebaseSettings() {
        secureSettings.firebaseApiKey = firebaseApiKey.ifBlank { null }
        secureSettings.firebaseProjectId = firebaseProjectId.ifBlank { null }
        secureSettings.firebaseAppId = firebaseAppId.ifBlank { null }
        secureSettings.firebaseWebClientId = firebaseWebClientId.ifBlank { null }
        isFirebaseConfigured = secureSettings.isFirebaseConfigured
    }

    /**
     * 保存済みのFirebase構成が前回適用時から変わっていたら、旧サインインを破棄して再初期化する。
     * 「編集が一段落した」タイミング（セクションを折りたたむ・画面を離れる・サインイン直前）で呼ぶ。
     */
    fun applyFirebaseConfigIfChanged() {
        val stored = storedFirebaseConfig()
        if (stored == appliedFirebaseConfig) return
        // Firebase(Google)の情報が変わったら既存のサインインは無効なので、
        // サインアウトして「ログインする」ボタンからやり直してもらう。
        // FirebaseAppProviderはまだ旧構成で初期化されたインスタンスを保持しているため、
        // サインアウト（旧FirebaseAppに対して行う必要がある）→リセットの順で実行する。
        googleAuthManager.signOut()
        signedInEmail = null
        viewModelScope.launch { cloudBackupSettings.setEnabled(false) }
        // 既存の初期化済みFirebaseAppは古い値を保持し続けるため、次回アクセス時に
        // 新しい値で再初期化されるようリセットする。
        FirebaseAppProvider.reset(getApplication<Application>())
        appliedFirebaseConfig = stored
    }

    /**
     * 全セクションの一括保存。フォーカス移動イベントを経ずに画面を離れた場合
     * （ON_PAUSE・画面破棄・サインインボタン押下）の保存漏れを防ぐ。
     */
    fun saveAll() {
        saveXBearerToken()
        saveR2Settings()
        saveFirebaseSettings()
        applyFirebaseConfigIfChanged()
    }

    fun dismissError() {
        errorMessage = null
    }

    /** 「ログインする」ボタンから呼ぶ。成功するとトグル表示に切り替わる。 */
    fun signIn() {
        // 入力欄にフォーカスが残ったままボタンを押した場合も、最新の入力値でサインインする
        saveAll()
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

    var transferState by mutableStateOf<TransferUiState>(TransferUiState.Idle)
        private set

    /**
     * 全設定をパスフレーズ暗号化して[uri]（SAFで選んだ保存先）へ書き出す。
     * 入力欄の編集途中の値も含めるため、先にsaveAll()で保存してから読み出す。
     */
    fun exportSettings(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            transferState = try {
                saveAll()
                withContext(Dispatchers.IO) {
                    val bytes = SettingsTransfer.encrypt(
                        SettingsTransfer.payloadFrom(secureSettings),
                        passphrase.toCharArray()
                    )
                    val context = getApplication<Application>()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw IllegalStateException("保存先を開けませんでした")
                }
                TransferUiState.Exported
            } catch (e: Exception) {
                TransferUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * [uri]の暗号化ファイルをパスフレーズで復号し、全設定を上書きする。
     * Firebase構成が変わるため、成功時はサインアウト→再初期化のカスケードも適用する。
     */
    fun importSettings(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            transferState = try {
                val payload = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("ファイルを開けませんでした")
                    SettingsTransfer.decrypt(bytes, passphrase.toCharArray())
                }
                SettingsTransfer.applyTo(payload, secureSettings)
                // 画面の入力欄にも反映する
                xBearerToken = secureSettings.xBearerToken.orEmpty()
                r2BucketName = secureSettings.r2BucketName.orEmpty()
                r2AccountId = secureSettings.r2AccountId.orEmpty()
                r2AccessKeyId = secureSettings.r2AccessKeyId.orEmpty()
                r2SecretAccessKey = secureSettings.r2SecretAccessKey.orEmpty()
                r2Endpoint = secureSettings.r2Endpoint.orEmpty()
                firebaseApiKey = secureSettings.firebaseApiKey.orEmpty()
                firebaseProjectId = secureSettings.firebaseProjectId.orEmpty()
                firebaseAppId = secureSettings.firebaseAppId.orEmpty()
                firebaseWebClientId = secureSettings.firebaseWebClientId.orEmpty()
                isFirebaseConfigured = secureSettings.isFirebaseConfigured
                applyFirebaseConfigIfChanged()
                TransferUiState.Imported
            } catch (e: Exception) {
                TransferUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun dismissTransferState() {
        transferState = TransferUiState.Idle
    }
}
