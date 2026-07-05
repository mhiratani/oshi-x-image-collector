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
    /** クラウドバックアップからの復元の進捗状態。 */
    sealed interface RestoreProgress {
        data object FetchingMetadata : RestoreProgress
        data class DownloadingImages(val completed: Int, val total: Int) : RestoreProgress
    }

    /** クラウドバックアップからの復元結果のサマリー。 */
    data class RestoreResult(
        val accountsRestored: Int,
        val mediaRowsRestored: Int,
        val imagesDownloaded: Int,
        val imagesFailed: Int
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
    suspend fun refreshAll(maxPagesPerAccount: Int = 3) {
        val client = xApiClient()
        val backupEnabled = cloudBackupSettings.isEnabled.first()

        for (account in targetAccountDao.getAll()) {
            try {
                val xUserId = account.xUserId ?: client.resolveUserId(account.screenName)?.also { resolved ->
                    targetAccountDao.update(account.copy(xUserId = resolved))
                } ?: run {
                    Log.w(TAG, "resolveUserId failed for @${account.screenName}")
                    continue
                }

                val result = client.fetchPhotoMedia(
                    userId = xUserId,
                    sinceId = account.lastFetchedId,
                    maxPages = maxPagesPerAccount
                )

                val newEntities = result.media.map { photo ->
                    val localPath = runCatching { imageStorage.download(photo.mediaKey, photo.url) }.getOrNull()
                    val faceResult = localPath
                        ?.let { FaceDetector.detect(File(it)) }
                        as? FaceDetector.Result.Detected
                    MediaAssetEntity(
                        mediaKey = photo.mediaKey,
                        tweetId = photo.tweetId,
                        xUserId = xUserId,
                        xCdnUrl = photo.url,
                        localImagePath = localPath,
                        r2BackupUrl = null,
                        postedAt = photo.postedAt,
                        createdAt = System.currentTimeMillis(),
                        isFace = faceResult?.isFace,
                        faceConfidence = faceResult?.confidence
                    )
                }
                if (newEntities.isNotEmpty()) {
                    mediaAssetDao.insertAll(newEntities)
                }

                val updatedAccount = account.copy(
                    xUserId = xUserId,
                    lastFetchedId = result.newestId ?: account.lastFetchedId,
                    lastCheckedAt = System.currentTimeMillis()
                )
                targetAccountDao.update(updatedAccount)

                if (backupEnabled) {
                    firestoreMirror.mirrorTargetAccount(updatedAccount)
                    firestoreMirror.mirrorMediaAssets(newEntities)
                    backupImages(newEntities)
                }
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed for @${account.screenName}", e)
            }
        }

        detectPendingFaces(backupEnabled)
    }

    /**
     * 顔検出モデルが未取得だった等の理由で判定できなかった画像（[frontend/worker/faceDetect.js]
     * と同じ対象条件: `isFace IS NULL AND NOT faceReviewed`）をまとめて再試行する。
     * クラウドバックアップの有無に関わらず常に実行する（顔判定はローカル機能のため）。
     */
    private suspend fun detectPendingFaces(backupEnabled: Boolean) {
        val pending = mediaAssetDao.getPendingFaceDetection()
        if (pending.isEmpty()) return

        val updated = mutableListOf<MediaAssetEntity>()
        for (asset in pending) {
            val localPath = asset.localImagePath ?: continue
            when (val result = FaceDetector.detect(File(localPath))) {
                is FaceDetector.Result.Detected -> {
                    mediaAssetDao.updateFaceResult(asset.mediaKey, result.isFace, result.confidence)
                    updated += asset.copy(isFace = result.isFace, faceConfidence = result.confidence)
                }
                FaceDetector.Result.Unavailable -> Unit // 次回の「最新を取得」で再試行
            }
        }

        if (backupEnabled && updated.isNotEmpty()) {
            firestoreMirror.mirrorMediaAssets(updated)
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
     * クラウドバックアップからの復元。設定画面から手動で呼ぶ。
     * 既存のローカル行は上書きしない（追加のみ）ため、途中で失敗しても再実行すれば安全に再開できる。
     */
    suspend fun restoreFromCloud(onProgress: (RestoreProgress) -> Unit = {}): RestoreResult {
        onProgress(RestoreProgress.FetchingMetadata)
        val cloudAccounts = firestoreMirror.fetchTargetAccounts()
        val cloudMedia = firestoreMirror.fetchMediaAssets()

        var accountsRestored = 0
        for (account in cloudAccounts) {
            if (targetAccountDao.getByScreenName(account.screenName) == null) {
                targetAccountDao.insert(account)
                accountsRestored++
            }
        }

        val insertedIds = if (cloudMedia.isNotEmpty()) mediaAssetDao.insertAll(cloudMedia) else emptyList()
        val mediaRowsRestored = insertedIds.count { it != -1L }

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
