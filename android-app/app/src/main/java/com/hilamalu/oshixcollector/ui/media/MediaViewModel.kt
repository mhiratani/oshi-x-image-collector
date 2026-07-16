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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 絞り込みチップ1つ分の表示データ（Web版toolbarの「@name (枚数)」に対応）。 */
data class AccountChip(
    val screenName: String,
    val xUserId: String,
    val mediaCount: Int
)

// Firestore/ML Kitの呼び出しにタイムアウトが無く、通信が固まるとスピナーが無限に回り続けるため上限を設ける
private const val SYNC_TIMEOUT_MS = 120_000L

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
    private val favoritesOnly = MutableStateFlow(false)

    /** アカウント絞り込み（xUserId。null=すべて）。 */
    private val accountFilter = MutableStateFlow<String?>(null)

    val media: StateFlow<List<MediaAssetEntity>> =
        combine(repository.media, faceOnly, favoritesOnly, accountFilter) { assets, faceOnlyEnabled, favoritesOnlyEnabled, filter ->
            assets
                .let { if (filter == null) it else it.filter { a -> a.xUserId == filter } }
                .let { if (faceOnlyEnabled) it.filter { a -> a.isFace == true } else it }
                .let { if (favoritesOnlyEnabled) it.filter { a -> a.isFavorite } else it }
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

    /** 一覧末尾のバックフィルボタンの表示制御。これ以上遡れる対象アカウントが1件も無い（Web版のallDone）。 */
    val backfillAllDone: StateFlow<Boolean> =
        combine(repository.accounts, accountFilter) { accounts, filter ->
            val targets = accounts.filter { it.xUserId != null && !it.syncPaused }
                .let { if (filter == null) it else it.filter { a -> a.xUserId == filter } }
            targets.isEmpty() || targets.all { it.backfillDone }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val isFaceOnly: StateFlow<Boolean> = faceOnly
    val isFavoritesOnly: StateFlow<Boolean> = favoritesOnly
    val selectedAccountId: StateFlow<String?> = accountFilter

    var isRefreshing by mutableStateOf(false)
        private set

    var isBackfilling by mutableStateOf(false)
        private set

    /** 顔判定中の残り件数（0なら非表示）。「最新を取得」「過去の投稿を読み込む」の完了後に走る別枠の処理。 */
    var faceDetectionRemaining by mutableStateOf(0)
        private set

    private var faceDetectionJob: Job? = null
    private var faceDetectionRerunRequested = false

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var syncMessage by mutableStateOf<String?>(null)
        private set

    init {
        // 前回の顔判定が途中で中断された（画面を離れてviewModelScopeごとキャンセルされた等）
        // 未判定画像が残っていても、次の同期を待たずに処理できるよう起動時にも一度実行する
        runFaceDetection()
    }

    /**
     * 「最新を取得」。クラウドバックアップ有効かつサインイン済みなら、先にクラウドの
     * 最新状態をローカルへ取り込み（pull）、続けてX APIから新着を取得する。
     * クラウド同期の失敗はX取得を妨げない（エラー表示のみ）。
     */
    fun refresh() = launchSyncTask(
        isBusy = { isRefreshing },
        setBusy = { isRefreshing = it },
        timeoutMessage = "同期がタイムアウトしました。ネットワーク状況を確認してもう一度お試しください。"
    ) {
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
    }

    /**
     * 一覧末尾の「過去の投稿をさらに読み込む」から呼ぶ。
     * Web版と同じく、1アカウントだけ絞り込み中はそのアカウントのみを対象にする。
     */
    fun backfill() = launchSyncTask(
        isBusy = { isBackfilling },
        setBusy = { isBackfilling = it },
        timeoutMessage = "取得がタイムアウトしました。ネットワーク状況を確認してもう一度お試しください。"
    ) {
        repository.backfillAll(targetXUserId = accountFilter.value)
    }

    /** 同期系操作の共通枠: 多重起動ガード→タイムアウト付き実行→エラー表示→完了後に顔判定。 */
    private fun launchSyncTask(
        isBusy: () -> Boolean,
        setBusy: (Boolean) -> Unit,
        timeoutMessage: String,
        block: suspend () -> Unit
    ) {
        if (isBusy()) return
        viewModelScope.launch {
            setBusy(true)
            errorMessage = null
            try {
                withTimeout(SYNC_TIMEOUT_MS) { block() }
            } catch (e: TimeoutCancellationException) {
                errorMessage = timeoutMessage
            } catch (e: Exception) {
                errorMessage = e.message?.let(::friendlyApiError)
            } finally {
                setBusy(false)
            }
            // 顔判定はクラウドバックアップまでの完了を待たせず、別枠のインジケータで進める
            runFaceDetection()
        }
    }

    /** 未判定画像の顔判定をまとめて行う。実行中に再度呼ばれた場合は、完了後にもう一度実行する。 */
    private fun runFaceDetection() {
        if (faceDetectionJob?.isActive == true) {
            // 実行中の判定は開始時点の未判定リストしか見ないため、後から増えた分を取りこぼさないよう再実行を予約する
            faceDetectionRerunRequested = true
            return
        }
        faceDetectionJob = viewModelScope.launch {
            do {
                faceDetectionRerunRequested = false
                try {
                    repository.detectPendingFaces { remaining -> faceDetectionRemaining = remaining }
                } catch (e: Exception) {
                    errorMessage = e.message?.let(::friendlyApiError)
                } finally {
                    faceDetectionRemaining = 0
                }
            } while (faceDetectionRerunRequested)
        }
    }

    /** 拡大表示からの単発更新（顔判定上書き・お気に入り）を共通のエラーハンドリングで実行する。 */
    private fun launchMutation(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    /** 拡大表示からの顔判定の手動上書き（Web版のtoggleFace移植）。 */
    fun overrideFace(mediaKey: String, isFace: Boolean) = launchMutation {
        repository.overrideFace(mediaKey, isFace)
    }

    fun setFaceOnly(enabled: Boolean) {
        faceOnly.value = enabled
    }

    fun setFavoritesOnly(enabled: Boolean) {
        favoritesOnly.value = enabled
    }

    /** 拡大表示からのお気に入りON/OFF切り替え。 */
    fun toggleFavorite(mediaKey: String, isFavorite: Boolean) = launchMutation {
        repository.setFavorite(mediaKey, isFavorite)
    }

    /** ユーザー絞り込みシートでの選択。nullで「すべて」に戻す。 */
    fun selectAccountFilter(xUserId: String?) {
        accountFilter.value = xUserId
    }

    /** 端末の「戻る」操作からの一括解除。全フィルターを未適用状態に戻す。 */
    fun clearFilters() {
        accountFilter.value = null
        faceOnly.value = false
        favoritesOnly.value = false
    }

    fun dismissError() {
        errorMessage = null
    }

    fun dismissSyncMessage() {
        syncMessage = null
    }
}
