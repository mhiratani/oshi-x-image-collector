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
    val allDone: Boolean
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

    /** アカウント絞り込み（xUserId。null=すべて）。 */
    private val accountFilter = MutableStateFlow<String?>(null)

    val media: StateFlow<List<MediaAssetEntity>> =
        combine(repository.media, faceOnly, accountFilter) { assets, faceOnlyEnabled, filter ->
            assets
                .let { if (filter == null) it else it.filter { a -> a.xUserId == filter } }
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
                .let { if (filter == null) it else it.filter { a -> a.xUserId == filter } }
            BackfillUiState(allDone = targets.isEmpty() || targets.all { it.backfillDone })
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BackfillUiState(allDone = true)
        )

    val isFaceOnly: StateFlow<Boolean> = faceOnly
    val selectedAccountId: StateFlow<String?> = accountFilter

    var isRefreshing by mutableStateOf(false)
        private set

    var isBackfilling by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var syncMessage by mutableStateOf<String?>(null)
        private set

    /**
     * 「最新を取得」。クラウドバックアップ有効かつサインイン済みなら、先にクラウドの
     * 最新状態をローカルへ取り込み（pull）、続けてX APIから新着を取得する。
     * クラウド同期の失敗はX取得を妨げない（エラー表示のみ）。
     */
    fun refresh() {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            errorMessage = null
            try {
                val messages = mutableListOf<String>()

                if (cloudBackupSettings.isEnabled.first() && googleAuthManager.currentUser != null) {
                    try {
                        val synced = repository.restoreFromCloud()
                        if (synced.mediaRowsRestored > 0 || synced.imagesDownloaded > 0 || synced.accountsRestored > 0) {
                            messages += "クラウドからメタデータ${synced.mediaRowsRestored}件・画像${synced.imagesDownloaded}件を同期"
                        }
                    } catch (e: Exception) {
                        errorMessage = "クラウド同期に失敗しました: ${e.message}"
                    }
                }

                val result = repository.refreshAll()
                messages +=
                    if (result.newMediaCount > 0) "新着画像 ${result.newMediaCount}枚を取得しました"
                    else "新着はありませんでした"
                syncMessage = messages.joinToString("、")

                if (result.failedScreenNames.isNotEmpty()) {
                    val names = result.failedScreenNames.joinToString(", ") { "@$it" }
                    errorMessage = "$names の取得に失敗しました" +
                        (result.firstError?.let { ": ${friendlyApiError(it)}" } ?: "")
                }
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
        viewModelScope.launch {
            isBackfilling = true
            errorMessage = null
            try {
                repository.backfillAll(targetXUserId = accountFilter.value)
            } catch (e: Exception) {
                errorMessage = e.message?.let(::friendlyApiError)
            } finally {
                isBackfilling = false
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

    /** ユーザー絞り込みシートでの選択。nullで「すべて」に戻す。 */
    fun selectAccountFilter(xUserId: String?) {
        accountFilter.value = xUserId
    }

    fun dismissError() {
        errorMessage = null
    }

    fun dismissSyncMessage() {
        syncMessage = null
    }
}
