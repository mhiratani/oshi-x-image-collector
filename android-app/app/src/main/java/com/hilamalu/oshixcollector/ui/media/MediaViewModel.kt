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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 絞り込みチップ1つ分の表示データ（Web版toolbarの「@name (枚数)」に対応）。 */
data class AccountChip(
    val screenName: String,
    val xUserId: String,
    val mediaCount: Int
)

/** 一覧末尾のバックフィル操作の状態（Web版のbackfill statusに対応）。 */
data class BackfillUiState(
    /** これ以上遡れる対象アカウントが1件も無い（Web版のallDone）。 */
    val allDone: Boolean,
    /** 複数アカウント絞り込み中はバックフィル不可（Web版と同じ制約）。 */
    val multiFilterSelected: Boolean
)

// X APIの生エラーをユーザー向けメッセージに変換（Web版page.tsxのfriendlyApiError移植）
private fun friendlyApiError(msg: String): String {
    if (msg.contains("CreditsDepleted"))
        return "X APIのクレジットが不足しています。開発者ポータルでクレジットを追加してください。取得済みの分までは保存されています。"
    if (msg.contains("UsageCapExceeded"))
        return "X APIの月間利用上限に達しています。プランの確認が必要です。"
    if (msg.contains("rate limited") || msg.contains("429"))
        return "X APIのレート制限中です。しばらく待ってから再実行してください。"
    if (msg.contains("client-not-enrolled"))
        return "X開発者AppがProjectに紐付いていません。開発者ポータルで設定してください。"
    if (msg.contains("401"))
        return "X APIの認証に失敗しました。Bearer Tokenを確認してください。"
    if (msg.startsWith("X API"))
        return "X APIエラー: ${msg.take(160)}"
    return msg
}

class MediaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository(application)
    private val cloudBackupSettings = CloudBackupSettings(application)
    private val googleAuthManager = GoogleAuthManager(application)

    private val faceOnly = MutableStateFlow(false)

    /** アカウント絞り込み（xUserIdの集合。空=すべて）。Web版のfilter: string[]に対応。 */
    private val accountFilter = MutableStateFlow<Set<String>>(emptySet())

    val media: StateFlow<List<MediaAssetEntity>> =
        combine(repository.media, faceOnly, accountFilter) { assets, faceOnlyEnabled, filter ->
            assets
                .let { if (filter.isEmpty()) it else it.filter { a -> a.xUserId in filter } }
                .let { if (faceOnlyEnabled) it.filter { a -> a.isFace == true } else it }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 絞り込みチップ用: xUserId解決済みの追跡アカウントと収集枚数（Web版/api/accountsのmedia_count相当）。 */
    val accountChips: StateFlow<List<AccountChip>> =
        combine(repository.accounts, repository.media) { accounts, assets ->
            val countByUser = assets.groupingBy { it.xUserId }.eachCount()
            accounts.mapNotNull { account ->
                val xUserId = account.xUserId ?: return@mapNotNull null
                AccountChip(account.screenName, xUserId, countByUser[xUserId] ?: 0)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** タイルのバッジや拡大表示のメタ情報用: xUserId→screenName。 */
    val screenNameByUserId: StateFlow<Map<String, String>> =
        repository.accounts
            .map { accounts -> accounts.mapNotNull { a -> a.xUserId?.let { it to a.screenName } }.toMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 一覧末尾のバックフィルボタンの表示制御。 */
    val backfillState: StateFlow<BackfillUiState> =
        combine(repository.accounts, accountFilter) { accounts, filter ->
            val targets = accounts.filter { it.xUserId != null }
                .let { if (filter.isEmpty()) it else it.filter { a -> a.xUserId in filter } }
            BackfillUiState(
                allDone = targets.isEmpty() || targets.all { it.backfillDone },
                multiFilterSelected = filter.size > 1
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BackfillUiState(allDone = true, multiFilterSelected = false)
        )

    val isFaceOnly: StateFlow<Boolean> = faceOnly
    val selectedAccountIds: StateFlow<Set<String>> = accountFilter

    /** トップバーの同期アイコンの表示条件。クラウドバックアップ無効時はアイコン自体を出さない。 */
    val isCloudBackupEnabled: StateFlow<Boolean> =
        cloudBackupSettings.isEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    var isRefreshing by mutableStateOf(false)
        private set

    /** トップバーの同期アイコンの実行中状態。「最新を取得」（[isRefreshing]）とは別の操作。 */
    var isSyncing by mutableStateOf(false)
        private set

    var isBackfilling by mutableStateOf(false)
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
                errorMessage = e.message?.let(::friendlyApiError)
            } finally {
                isRefreshing = false
            }
        }
    }

    /**
     * 一覧末尾の「過去の投稿をさらに読み込む」から呼ぶ。
     * Web版と同じく、1アカウントだけ絞り込み中はそのアカウントのみを対象にする。
     */
    fun backfill() {
        if (isBackfilling) return
        val filter = accountFilter.value
        if (filter.size > 1) return
        viewModelScope.launch {
            isBackfilling = true
            errorMessage = null
            try {
                repository.backfillAll(targetXUserId = filter.singleOrNull())
            } catch (e: Exception) {
                errorMessage = e.message?.let(::friendlyApiError)
            } finally {
                isBackfilling = false
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

    /** 拡大表示からの顔判定の手動上書き（Web版のtoggleFace移植）。 */
    fun overrideFace(mediaKey: String, isFace: Boolean) {
        viewModelScope.launch {
            try {
                repository.overrideFace(mediaKey, isFace)
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun setFaceOnly(enabled: Boolean) {
        faceOnly.value = enabled
    }

    /** アカウントチップのタップ（Web版と同じトグル動作）。 */
    fun toggleAccountFilter(xUserId: String) {
        accountFilter.update { current ->
            if (xUserId in current) current - xUserId else current + xUserId
        }
    }

    /** 「すべて」チップのタップ。 */
    fun clearAccountFilter() {
        accountFilter.value = emptySet()
    }

    fun dismissError() {
        errorMessage = null
    }

    fun dismissSyncMessage() {
        syncMessage = null
    }
}
