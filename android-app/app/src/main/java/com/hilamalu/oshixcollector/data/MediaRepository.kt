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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * X取得→Room保存→（バックアップON時のみ）Firestore/R2ミラー、を一連でまとめるリポジトリ。
 * design.md 3.1.1/3.1.2のデータフローに対応する。
 */
class MediaRepository(context: Context) {
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

    private companion object {
        const val TAG = "MediaRepository"
    }
}
