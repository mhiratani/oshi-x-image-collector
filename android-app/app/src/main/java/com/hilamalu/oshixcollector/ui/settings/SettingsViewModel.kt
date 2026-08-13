package com.hilamalu.oshixcollector.ui.settings

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.MediaRepository
import com.hilamalu.oshixcollector.data.backup.CloudBackupSettings
import com.hilamalu.oshixcollector.data.backup.FirebaseAppProvider
import com.hilamalu.oshixcollector.data.backup.GoogleAuthManager
import com.hilamalu.oshixcollector.data.settings.SecureSettings
import com.hilamalu.oshixcollector.data.settings.SettingsTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

/** 機種変更用データパック（画像＋メタデータのZIP）の書き出し/取り込みの状態。 */
sealed interface DataPackUiState {
    data object Idle : DataPackUiState
    data class Exporting(val completed: Int, val total: Int) : DataPackUiState
    data class Importing(val completed: Int, val total: Int) : DataPackUiState
    data class Exported(val imageCount: Int) : DataPackUiState
    data class Imported(val result: MediaRepository.DataPackResult) : DataPackUiState
    data class Failed(val message: String) : DataPackUiState
}

sealed interface RestoreUiState {
    data object Idle : RestoreUiState
    data class InProgress(val progress: MediaRepository.RestoreProgress) : RestoreUiState
    data class Success(val result: MediaRepository.RestoreResult) : RestoreUiState
    data class Failed(val message: String) : RestoreUiState
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val secureSettings = SecureSettings(application)
    private val cloudBackupSettings = CloudBackupSettings(application)
    private val googleAuthManager = GoogleAuthManager(application)
    private val repository = MediaRepository(application)

    /**
     * マスタートグルの状態（＝クラウドバックアップを使う意思）。
     * Firebase未設定・未サインインでもONにでき、ONの間だけR2/Firebaseの入力欄を表示する。
     */
    val backupRequested: StateFlow<Boolean> = cloudBackupSettings.isRequested
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 実際にミラーが走る状態か（MediaRepositoryが見るフラグ）。
     * [backupRequested]と必要な設定が全て揃った時にだけtrueになる。
     */
    val cloudBackupEnabled: StateFlow<Boolean> = cloudBackupSettings.isEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * ローカルにデータがあるか。復元カードを出すかの判定に使う。
     * 初期値trueにして、既存ユーザーに一瞬カードが見えてしまうのを避ける。
     */
    val hasLocalData: StateFlow<Boolean> = repository.accounts
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * まだクラウドから取り込めていない画像の件数。
     * ローカルにデータがあっても、前回の復元が画像ダウンロード中に失敗していれば1件以上残る。
     * これを見ずに「ローカルが空か」だけで判定すると、復元はアカウントを先に書き込む
     * （[MediaRepository.restoreFromCloud]）ため、再開したい場面でカードが消えてしまう。
     */
    var missingImageCount by mutableStateOf(0)
        private set

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

    /** R2が揃っているか。未設定でもメタデータのバックアップは動くため、UIでは「任意」として扱う。 */
    var isR2Configured by mutableStateOf(secureSettings.isR2Configured)
        private set

    var signedInEmail by mutableStateOf(googleAuthManager.currentUser?.email)
        private set

    /** Firebase構成の変更に伴ってサインアウトした直後か。再ログインを促す案内の表示に使う。 */
    var signedOutByConfigChange by mutableStateOf(false)
        private set

    /** Snackbarに出すエラー。発生箇所ごとに文脈を含んだ完成形の文言を入れる。 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var restoreState by mutableStateOf<RestoreUiState>(RestoreUiState.Idle)
        private set

    /**
     * FirebaseAppProviderが現在参照している（＝前回[applyFirebaseConfigIfChanged]を通過した）
     * Firebase構成のスナップショット。保存済みの値と比べて再初期化の要否を判定する。
     */
    private var appliedFirebaseConfig = storedFirebaseConfig()

    init {
        // 前回の操作が途中で終わった場合（設定を埋めた直後にアプリを閉じた等）に備えて、
        // 画面を開いた時点で実効フラグを意思＋設定状況に合わせ直す。
        syncEffectiveEnabled()
        refreshMissingImageCount()
        refreshLocalDataSize()
    }

    /** 復元カードの表示判定に使う残件数を数え直す。画面を開いた時と復元完了時に呼ぶ。 */
    private fun refreshMissingImageCount() {
        viewModelScope.launch {
            missingImageCount = try {
                repository.countMissingLocalImages()
            } catch (e: Exception) {
                0
            }
        }
    }

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
        isR2Configured = secureSettings.isR2Configured
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
        val wasSignedIn = signedInEmail != null
        googleAuthManager.signOut()
        signedInEmail = null
        // 黙ってサインアウトされると理由が分からないため、画面に再ログインを促す案内を出す
        if (wasSignedIn) signedOutByConfigChange = true
        // 既存の初期化済みFirebaseAppは古い値を保持し続けるため、次回アクセス時に
        // 新しい値で再初期化されるようリセットする。
        FirebaseAppProvider.reset(getApplication<Application>())
        appliedFirebaseConfig = stored
        syncEffectiveEnabled()
    }

    fun dismissSignedOutNotice() {
        signedOutByConfigChange = false
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

    private fun message(@StringRes templateRes: Int, e: Exception): String =
        getApplication<Application>().getString(templateRes, e.message ?: e.javaClass.simpleName)

    /** マスタートグル。ONにしただけでは実際のバックアップは始まらない（[applyEffectiveEnabled]が判断する）。 */
    fun setBackupRequested(requested: Boolean) {
        viewModelScope.launch {
            cloudBackupSettings.setRequested(requested)
            applyEffectiveEnabled()
        }
    }

    /** バックアップに必要な設定（Firebase構成 + Googleサインイン）が揃っているか。 */
    private fun isBackupReady(): Boolean =
        isFirebaseConfigured && googleAuthManager.currentUser != null

    /**
     * 「使う意思」と設定状況から実効フラグ（[CloudBackupSettings.isEnabled]）を計算し直す。
     * OFF→ONに変わった時だけ、溜まっていた未バックアップ分をまとめて送る。
     */
    private suspend fun applyEffectiveEnabled() {
        val target = cloudBackupSettings.isRequested.first() && isBackupReady()
        if (target == cloudBackupSettings.isEnabled.first()) return
        cloudBackupSettings.setEnabled(target)
        if (target) {
            try {
                repository.backupExistingIfEnabled()
            } catch (e: Exception) {
                errorMessage = message(R.string.settings_backup_start_failed, e)
            }
        }
    }

    /** [applyEffectiveEnabled]をsuspendでない場所（トグル以外の同期的な操作）から呼ぶためのラッパー。 */
    private fun syncEffectiveEnabled() {
        viewModelScope.launch { applyEffectiveEnabled() }
    }

    /** 「Googleでログインする」ボタンから呼ぶ。成功すると条件が揃いバックアップが動き出す。 */
    fun signIn() {
        // 入力欄にフォーカスが残ったままボタンを押した場合も、最新の入力値でサインインする
        saveAll()
        viewModelScope.launch {
            try {
                signedInEmail = googleAuthManager.signIn().email
                signedOutByConfigChange = false
                applyEffectiveEnabled()
            } catch (e: Exception) {
                errorMessage = message(R.string.settings_sign_in_failed, e)
            }
        }
    }

    /** 「サインアウト」ボタンから呼ぶ。サインアウト中はミラーできないため実効フラグもOFFになる。 */
    fun signOut() {
        googleAuthManager.signOut()
        signedInEmail = null
        signedOutByConfigChange = false
        syncEffectiveEnabled()
    }

    fun restoreFromCloud() {
        if (restoreState is RestoreUiState.InProgress) return
        viewModelScope.launch {
            restoreState = RestoreUiState.InProgress(MediaRepository.RestoreProgress.FetchingMetadata)
            try {
                if (googleAuthManager.currentUser == null) {
                    signedInEmail = googleAuthManager.signIn().email
                    applyEffectiveEnabled()
                }
                val result = repository.restoreFromCloud { progress ->
                    restoreState = RestoreUiState.InProgress(progress)
                }
                restoreState = RestoreUiState.Success(result)
            } catch (e: Exception) {
                restoreState = RestoreUiState.Failed(e.message ?: "復元に失敗しました")
            }
            // 成功・失敗どちらでも残件数は変わるため、カードの表示判定を更新する
            refreshMissingImageCount()
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
                isR2Configured = secureSettings.isR2Configured
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

    // ── 機種変更用データパック（画像＋メタデータのZIP） ──

    var dataPackState by mutableStateOf<DataPackUiState>(DataPackUiState.Idle)
        private set

    /** 書き出し前に見せる現在のデータ量（画像枚数, 合計バイト数）。 */
    var localDataSize by mutableStateOf(0 to 0L)
        private set

    fun refreshLocalDataSize() {
        viewModelScope.launch {
            localDataSize = try {
                repository.localDataSize()
            } catch (e: Exception) {
                0 to 0L
            }
        }
    }

    fun exportDataPack(uri: Uri) {
        if (dataPackState is DataPackUiState.Exporting) return
        viewModelScope.launch {
            dataPackState = DataPackUiState.Exporting(0, 0)
            dataPackState = try {
                val context = getApplication<Application>()
                val imageCount = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        repository.exportDataPack(out) { progress ->
                            dataPackState = DataPackUiState.Exporting(progress.completed, progress.total)
                        }
                    } ?: throw IllegalStateException("保存先を開けませんでした")
                }
                DataPackUiState.Exported(imageCount)
            } catch (e: Exception) {
                DataPackUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun importDataPack(uri: Uri) {
        if (dataPackState is DataPackUiState.Importing) return
        viewModelScope.launch {
            dataPackState = DataPackUiState.Importing(0, 0)
            dataPackState = try {
                val context = getApplication<Application>()
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        repository.importDataPack(input) { progress ->
                            dataPackState = DataPackUiState.Importing(progress.completed, progress.total)
                        }
                    } ?: throw IllegalStateException("ファイルを開けませんでした")
                }
                DataPackUiState.Imported(result)
            } catch (e: Exception) {
                DataPackUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
            refreshLocalDataSize()
            // 取り込みで画像が埋まると復元カードの表示条件も変わる
            refreshMissingImageCount()
        }
    }

    fun dismissDataPackState() {
        dataPackState = DataPackUiState.Idle
    }
}
