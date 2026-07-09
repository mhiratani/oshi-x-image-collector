package com.hilamalu.oshixcollector.data

import android.content.Context
import android.util.Log
import com.hilamalu.oshixcollector.data.backup.CloudBackupSettings
import com.hilamalu.oshixcollector.data.backup.FirestoreMirror
import com.hilamalu.oshixcollector.data.backup.GoogleAuthManager
import com.hilamalu.oshixcollector.data.backup.R2Uploader
import com.hilamalu.oshixcollector.data.db.AppDatabase
import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import com.hilamalu.oshixcollector.data.db.TargetAccountEntity
import com.hilamalu.oshixcollector.data.face.FaceDetector
import com.hilamalu.oshixcollector.data.settings.SecureSettings
import com.hilamalu.oshixcollector.data.xapi.XApiClient
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * X取得→Room保存→（バックアップON時のみ）Firestore/R2ミラー、を一連でまとめるリポジトリ。
 * design.md 3.1.1/3.1.2のデータフローに対応する。
 */
class MediaRepository(context: Context) {
    /** クラウドバックアップからの復元/同期の進捗状態。 */
    sealed interface RestoreProgress {
        data object FetchingMetadata : RestoreProgress
        data class DownloadingImages(val completed: Int, val total: Int) : RestoreProgress
    }

    /** クラウドバックアップからの復元/同期結果のサマリー。 */
    data class RestoreResult(
        val accountsRestored: Int,
        val mediaRowsRestored: Int,
        val imagesDownloaded: Int,
        val imagesFailed: Int
    )

    /** 「最新を取得」の実行結果サマリー（スナックバー表示用）。 */
    data class RefreshResult(
        /** 今回新たに保存した画像の枚数（重複除外後）。 */
        val newMediaCount: Int,
        /** 取得に失敗したアカウント（screenName）。 */
        val failedScreenNames: List<String>,
        /** 最初に発生したエラーメッセージ（friendlyApiError変換前の生メッセージ）。 */
        val firstError: String?
    )

    private val db = AppDatabase.getDatabase(context)
    private val targetAccountDao = db.targetAccountDao()
    private val mediaAssetDao = db.mediaAssetDao()

    private val secureSettings = SecureSettings(context)
    private val imageStorage = ImageStorage(context)
    private val cloudBackupSettings = CloudBackupSettings(context)
    private val googleAuthManager = GoogleAuthManager(context)
    private val firestoreMirror = FirestoreMirror(context, googleAuthManager)
    private val r2Uploader = R2Uploader(secureSettings)

    val accounts: Flow<List<TargetAccountEntity>> = targetAccountDao.observeAll()
    val media: Flow<List<MediaAssetEntity>> = mediaAssetDao.observeAll()

    /** オンボーディング画面から呼ぶ。追跡アカウントが1件も無ければ「ローカルデータが空」とみなす。 */
    suspend fun hasAnyAccounts(): Boolean = targetAccountDao.count() > 0

    private fun xApiClient(): XApiClient {
        val token = secureSettings.xBearerToken
        require(!token.isNullOrBlank()) { "設定画面でX Bearer Tokenを入力してください" }
        return XApiClient(token)
    }

    suspend fun addAccount(screenName: String) {
        val normalized = screenName.removePrefix("@").trim()
        require(normalized.isNotEmpty()) { "アカウント名を入力してください" }
        val now = System.currentTimeMillis()
        targetAccountDao.insert(
            TargetAccountEntity(
                screenName = normalized,
                xUserId = null,
                lastFetchedId = null,
                lastCheckedAt = null,
                createdAt = now
            )
        )
    }

    suspend fun removeAccount(screenName: String) {
        val account = targetAccountDao.getByScreenName(screenName) ?: return
        account.xUserId?.let { xUserId ->
            for (asset in mediaAssetDao.observeByAccount(xUserId).first()) {
                imageStorage.delete(asset.mediaKey)
            }
            mediaAssetDao.deleteByAccount(xUserId)
        }
        targetAccountDao.deleteByScreenName(screenName)
    }

    /** 「最新を取得」ボタンから呼ぶ。全追跡アカウントについて新着を取得しローカル保存する。 */
    suspend fun refreshAll(maxPagesPerAccount: Int = 3): RefreshResult {
        val client = xApiClient()
        val backupEnabled = cloudBackupSettings.isEnabled.first()

        var newMediaCount = 0
        val failedScreenNames = mutableListOf<String>()
        var firstError: String? = null

        for (account in targetAccountDao.getAll()) {
            try {
                val isInitialCrawl = account.lastFetchedId == null

                val xUserId = account.xUserId ?: run {
                    val resolved = client.resolveUserId(account.screenName)
                    firestoreMirror.logApiUsage(
                        purpose = "resolve",
                        endpoint = "users/by/username/:screen_name",
                        screenName = account.screenName,
                        resource = "user_read",
                        quantity = if (resolved != null) 1 else 0
                    )
                    if (resolved != null) {
                        targetAccountDao.update(account.copy(xUserId = resolved))
                    } else {
                        Log.w(TAG, "resolveUserId failed for @${account.screenName}")
                    }
                    resolved
                }
                if (xUserId == null) {
                    failedScreenNames += account.screenName
                    if (firstError == null) firstError = "ユーザーIDの解決に失敗しました"
                    continue
                }

                var tweetsCount = 0
                val result = client.fetchPhotoMedia(
                    userId = xUserId,
                    sinceId = account.lastFetchedId,
                    maxPages = maxPagesPerAccount,
                    onPage = { n -> tweetsCount += n }
                )
                firestoreMirror.logApiUsage(
                    purpose = "collect",
                    endpoint = "users/:id/tweets",
                    screenName = account.screenName,
                    resource = "posts_read",
                    quantity = tweetsCount
                )

                // 顔判定はクラウドバックアップ完了後に別枠(detectPendingFaces)でまとめて行うため、ここでは行わない
                val newEntities = result.media.map { photo ->
                    val localPath = runCatching { imageStorage.download(photo.mediaKey, photo.url) }.getOrNull()
                    MediaAssetEntity(
                        mediaKey = photo.mediaKey,
                        tweetId = photo.tweetId,
                        xUserId = xUserId,
                        xCdnUrl = photo.url,
                        localImagePath = localPath,
                        r2BackupUrl = null,
                        postedAt = photo.postedAt,
                        createdAt = System.currentTimeMillis(),
                        isFace = null,
                        faceConfidence = null
                    )
                }
                if (newEntities.isNotEmpty()) {
                    // IGNORE-on-conflictのため、実際に挿入された行だけを数える
                    newMediaCount += mediaAssetDao.insertAll(newEntities).count { it != -1L }
                }

                var updatedAccount = account.copy(
                    xUserId = xUserId,
                    lastFetchedId = result.newestId ?: account.lastFetchedId,
                    lastCheckedAt = System.currentTimeMillis()
                )
                // 初回クロール時は「どこまで遡ったか」をバックフィルの起点として記録
                // （Web版のsetBackfillCursorIfEmpty相当。既に値がある場合は上書きしない）
                if (isInitialCrawl && account.backfillCursor == null && result.oldestId != null) {
                    updatedAccount = updatedAccount.copy(backfillCursor = result.oldestId)
                }
                targetAccountDao.update(updatedAccount)

                if (backupEnabled) {
                    firestoreMirror.mirrorTargetAccount(updatedAccount)
                    firestoreMirror.mirrorMediaAssets(newEntities)
                    backupImages(newEntities)
                }
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed for @${account.screenName}", e)
                failedScreenNames += account.screenName
                if (firstError == null) firstError = e.message
            }
        }

        return RefreshResult(newMediaCount, failedScreenNames, firstError)
    }

    /**
     * 「過去の投稿をさらに読み込む」ボタンから呼ぶ。`backfillDone == false`の追跡アカウントについて、
     * 過去方向（`until_id`）に遡って投稿を取得する（Web版`worker/batch.js`のbackfillAllAccounts移植）。
     * `backfillCursor`が空の場合はローカルDB内のそのアカウントの最古tweetIdを起点にする。
     * [targetXUserId]を指定した場合はそのアカウントだけを対象にする（Web版の1件絞り込み中バックフィル相当）。
     */
    suspend fun backfillAll(maxPagesPerAccount: Int = 5, targetXUserId: String? = null) {
        val client = xApiClient()
        val backupEnabled = cloudBackupSettings.isEnabled.first()

        for (account in targetAccountDao.getAll()) {
            val xUserId = account.xUserId
            if (xUserId == null || account.backfillDone) continue
            if (targetXUserId != null && xUserId != targetXUserId) continue

            try {
                val untilId = account.backfillCursor ?: mediaAssetDao.getOldestTweetId(xUserId)

                var tweetsCount = 0
                val result = client.fetchPhotoMedia(
                    userId = xUserId,
                    untilId = untilId,
                    maxPages = maxPagesPerAccount,
                    onPage = { n -> tweetsCount += n }
                )
                firestoreMirror.logApiUsage(
                    purpose = "backfill",
                    endpoint = "users/:id/tweets",
                    screenName = account.screenName,
                    resource = "posts_read",
                    quantity = tweetsCount
                )

                // 顔判定はクラウドバックアップ完了後に別枠(detectPendingFaces)でまとめて行うため、ここでは行わない
                val newEntities = result.media.map { photo ->
                    val localPath = runCatching { imageStorage.download(photo.mediaKey, photo.url) }.getOrNull()
                    MediaAssetEntity(
                        mediaKey = photo.mediaKey,
                        tweetId = photo.tweetId,
                        xUserId = xUserId,
                        xCdnUrl = photo.url,
                        localImagePath = localPath,
                        r2BackupUrl = null,
                        postedAt = photo.postedAt,
                        createdAt = System.currentTimeMillis(),
                        isFace = null,
                        faceConfidence = null
                    )
                }
                if (newEntities.isNotEmpty()) {
                    mediaAssetDao.insertAll(newEntities)
                }

                val updatedAccount = account.copy(
                    backfillCursor = result.oldestId ?: untilId,
                    backfillDone = result.exhausted
                )
                targetAccountDao.update(updatedAccount)

                if (backupEnabled) {
                    firestoreMirror.mirrorTargetAccount(updatedAccount)
                    firestoreMirror.mirrorMediaAssets(newEntities)
                    backupImages(newEntities)
                }

                Log.i(
                    TAG,
                    "backfill @${account.screenName}: ${newEntities.size} photos" +
                        if (updatedAccount.backfillDone) " — 完了（これ以上過去はありません）" else " (cursor: ${updatedAccount.backfillCursor})"
                )
            } catch (e: Exception) {
                Log.w(TAG, "backfill failed for @${account.screenName}", e)
            }
        }
    }

    /**
     * 未判定（[frontend/worker/faceDetect.js]と同じ対象条件: `isFace IS NULL AND NOT faceReviewed`）の
     * 画像をまとめて顔判定する。「最新を取得」「過去の投稿を読み込む」の完了後に呼ぶ想定
     * （クラウドバックアップ完了を顔判定の完了で待たせないよう、あえて別枠の呼び出しにしている）。
     * クラウドバックアップの有無に関わらず常に実行する（顔判定はローカル機能のため）。
     */
    suspend fun detectPendingFaces(onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }) {
        val pending = mediaAssetDao.getPendingFaceDetection()
        if (pending.isEmpty()) return
        val backupEnabled = cloudBackupSettings.isEnabled.first()

        val updated = mutableListOf<MediaAssetEntity>()
        pending.forEachIndexed { index, asset ->
            val localPath = asset.localImagePath
            if (localPath != null) {
                when (val result = FaceDetector.detect(File(localPath))) {
                    is FaceDetector.Result.Detected -> {
                        mediaAssetDao.updateFaceResult(asset.mediaKey, result.isFace, result.confidence)
                        updated += asset.copy(isFace = result.isFace, faceConfidence = result.confidence)
                    }
                    FaceDetector.Result.Unavailable -> Unit // 次回に再試行
                }
            }
            onProgress(index + 1, pending.size)
        }

        if (backupEnabled && updated.isNotEmpty()) {
            firestoreMirror.mirrorMediaAssets(updated)
        }
    }

    /**
     * 拡大表示からの顔判定の手動上書き（Web版 `PATCH /api/media/[mediaKey]` の移植）。
     * `faceReviewed = true` になり以降の自動判定対象から外れる。クラウドバックアップON時はFirestoreにもミラーする。
     */
    suspend fun overrideFace(mediaKey: String, isFace: Boolean) {
        mediaAssetDao.overrideFace(mediaKey, isFace)
        if (cloudBackupSettings.isEnabled.first()) {
            mediaAssetDao.getByMediaKey(mediaKey)?.let { firestoreMirror.mirrorMediaAssets(listOf(it)) }
        }
    }

    /** 拡大表示からのお気に入りON/OFF切り替え。クラウドバックアップON時はFirestoreにもミラーする。 */
    suspend fun setFavorite(mediaKey: String, isFavorite: Boolean) {
        mediaAssetDao.setFavorite(mediaKey, isFavorite)
        if (cloudBackupSettings.isEnabled.first()) {
            mediaAssetDao.getByMediaKey(mediaKey)?.let { firestoreMirror.mirrorMediaAssets(listOf(it)) }
        }
    }

    /**
     * クラウドバックアップを新たにONにした直後に呼ぶ。OFFだった間にローカルへ
     * 溜まっていた未バックアップ分をまとめてFirestore/R2へミラーする。
     */
    suspend fun backupExistingIfEnabled() {
        if (!cloudBackupSettings.isEnabled.first()) return
        for (account in targetAccountDao.getAll()) {
            firestoreMirror.mirrorTargetAccount(account)
        }
        val pending = mediaAssetDao.getPendingBackup()
        if (pending.isEmpty()) return
        firestoreMirror.mirrorMediaAssets(pending)
        backupImages(pending)
    }

    /** バックアップ未実施（`r2BackupUrl == null`）の画像をR2へアップロードする。 */
    private suspend fun backupImages(assets: List<MediaAssetEntity>) {
        if (!secureSettings.isR2Configured) return
        for (asset in assets) {
            val localPath = asset.localImagePath ?: continue
            try {
                val bytes = File(localPath).readBytes()
                val r2Url = r2Uploader.upload(asset.mediaKey, asset.xUserId, bytes)
                mediaAssetDao.updateR2BackupUrl(asset.mediaKey, r2Url)
            } catch (e: Exception) {
                Log.w(TAG, "R2 backup failed for ${asset.mediaKey}", e)
                mediaAssetDao.incrementBackupAttempts(asset.mediaKey)
            }
        }
    }

    /**
     * クラウドバックアップからの復元/同期。オンボーディング初回復元、設定画面の「クラウドから復元」、
     * トップバーの同期アイコンのいずれからも呼ばれる一本化した実装。
     * ローカルに無い行は追加し、既存行はクラウド由来フィールドだけを上書きする
     * （[TargetAccountEntity]は全フィールドがクラウド由来のため単純upsert、[MediaAssetEntity]は
     * `localImagePath`/`backupAttempts`等のローカル専有フィールドを保持したまま更新する）。
     * 途中で失敗しても再実行すれば安全に再開できる。
     */
    suspend fun restoreFromCloud(onProgress: (RestoreProgress) -> Unit = {}): RestoreResult {
        onProgress(RestoreProgress.FetchingMetadata)
        val cloudAccounts = firestoreMirror.fetchTargetAccounts()
        val cloudMedia = firestoreMirror.fetchMediaAssets()

        var accountsRestored = 0
        for (account in cloudAccounts) {
            if (targetAccountDao.getByScreenName(account.screenName) == null) accountsRestored++
        }
        if (cloudAccounts.isNotEmpty()) {
            targetAccountDao.upsertAll(cloudAccounts)
        }

        var mediaRowsRestored = 0
        if (cloudMedia.isNotEmpty()) {
            val existingKeys = mediaAssetDao.getExistingMediaKeys(cloudMedia.map { it.mediaKey }).toSet()
            val newMedia = cloudMedia.filter { it.mediaKey !in existingKeys }
            val existingMedia = cloudMedia.filter { it.mediaKey in existingKeys }

            if (newMedia.isNotEmpty()) {
                val insertedIds = mediaAssetDao.insertAll(newMedia)
                mediaRowsRestored = insertedIds.count { it != -1L }
            }
            for (asset in existingMedia) {
                mediaAssetDao.updateCloudFields(
                    mediaKey = asset.mediaKey,
                    r2BackupUrl = asset.r2BackupUrl,
                    isFace = asset.isFace,
                    faceConfidence = asset.faceConfidence,
                    faceReviewed = asset.faceReviewed,
                    isFavorite = asset.isFavorite
                )
            }
        }

        val candidates = mediaAssetDao.getBackedUp()
            .filter { it.localImagePath == null || !File(it.localImagePath).exists() }
        val (downloaded, failed) = downloadMissingImages(candidates, onProgress)

        return RestoreResult(accountsRestored, mediaRowsRestored, downloaded, failed)
    }

    private suspend fun downloadMissingImages(
        candidates: List<MediaAssetEntity>,
        onProgress: (RestoreProgress) -> Unit
    ): Pair<Int, Int> {
        if (candidates.isEmpty()) return 0 to 0
        val semaphore = Semaphore(RESTORE_DOWNLOAD_CONCURRENCY)
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val total = candidates.size

        coroutineScope {
            candidates.map { asset ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val bytes = r2Uploader.download(asset.mediaKey, asset.xUserId)
                            val path = imageStorage.saveBytes(asset.mediaKey, bytes)
                            mediaAssetDao.updateLocalImagePath(asset.mediaKey, path)
                        }.onSuccess {
                            successCount.incrementAndGet()
                        }.onFailure { e ->
                            Log.w(TAG, "restore image download failed for ${asset.mediaKey}", e)
                            failureCount.incrementAndGet()
                        }
                        onProgress(RestoreProgress.DownloadingImages(successCount.get() + failureCount.get(), total))
                    }
                }
            }.awaitAll()
        }

        return successCount.get() to failureCount.get()
    }

    private companion object {
        const val TAG = "MediaRepository"
        const val RESTORE_DOWNLOAD_CONCURRENCY = 4
    }
}
