package com.hilamalu.oshixcollector.data.backup

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import com.hilamalu.oshixcollector.data.db.TargetAccountEntity
import com.hilamalu.oshixcollector.data.settings.SecureSettings
import kotlinx.coroutines.tasks.await

/**
 * クラウドバックアップON時のみ使う、Room→Firestoreへの片方向ミラー書き込み。
 * design.md 3.1.2の通り、失敗してもローカルの動作には一切影響させない
 * （呼び出し元はfire-and-forgetで呼び、例外はここで握りつぶしてログのみ残す）。
 *
 * コレクション構成: users/{uid}/targetAccounts/{screenName}, users/{uid}/mediaAssets/{mediaKey}
 * セキュリティルールは android-app/firestore.rules 参照。
 */
class FirestoreMirror(
    private val context: Context,
    private val googleAuthManager: GoogleAuthManager
) {
    private val secureSettings = SecureSettings(context)

    private fun userDocOrNull(): DocumentReference? {
        val uid = googleAuthManager.currentUser?.uid ?: return null
        val app = FirebaseAppProvider.getOrNull(context, secureSettings) ?: return null
        return FirebaseFirestore.getInstance(app).collection("users").document(uid)
    }

    suspend fun mirrorTargetAccount(account: TargetAccountEntity) {
        val userDoc = userDocOrNull() ?: return
        runCatching {
            userDoc.collection("targetAccounts").document(account.screenName)
                .set(
                    mapOf(
                        "xUserId" to account.xUserId,
                        "lastFetchedId" to account.lastFetchedId,
                        "lastCheckedAt" to account.lastCheckedAt,
                        "createdAt" to account.createdAt
                    )
                )
                .await()
        }.onFailure { e -> Log.w(TAG, "mirrorTargetAccount failed: ${account.screenName}", e) }
    }

    suspend fun mirrorMediaAssets(assets: List<MediaAssetEntity>) {
        if (assets.isEmpty()) return
        val userDoc = userDocOrNull() ?: return
        runCatching {
            userDoc.firestore.runBatch { batch ->
                val collection = userDoc.collection("mediaAssets")
                for (asset in assets) {
                    batch.set(
                        collection.document(asset.mediaKey),
                        mapOf(
                            "tweetId" to asset.tweetId,
                            "xUserId" to asset.xUserId,
                            "xCdnUrl" to asset.xCdnUrl,
                            "r2BackupUrl" to asset.r2BackupUrl,
                            "postedAt" to asset.postedAt,
                            "createdAt" to asset.createdAt,
                            "isFace" to asset.isFace,
                            "faceConfidence" to asset.faceConfidence
                        )
                    )
                }
            }.await()
        }.onFailure { e -> Log.w(TAG, "mirrorMediaAssets failed (${assets.size} items)", e) }
    }

    /** クラウドバックアップからの復元用。未サインインの場合は例外を投げる（ユーザー起動アクションのため）。 */
    suspend fun fetchTargetAccounts(): List<TargetAccountEntity> {
        val userDoc = userDocOrNull() ?: error("Googleサインインが必要です")
        return userDoc.collection("targetAccounts").get().await().documents.map { doc ->
            TargetAccountEntity(
                screenName = doc.id,
                xUserId = doc.getString("xUserId"),
                lastFetchedId = doc.getString("lastFetchedId"),
                lastCheckedAt = doc.getLong("lastCheckedAt"),
                createdAt = doc.getLong("createdAt") ?: 0L
            )
        }
    }

    /** クラウドバックアップからの復元用。未サインインの場合は例外を投げる（ユーザー起動アクションのため）。 */
    suspend fun fetchMediaAssets(): List<MediaAssetEntity> {
        val userDoc = userDocOrNull() ?: error("Googleサインインが必要です")
        return userDoc.collection("mediaAssets").get().await().documents.mapNotNull { doc ->
            val tweetId = doc.getString("tweetId")
            val xUserId = doc.getString("xUserId")
            val xCdnUrl = doc.getString("xCdnUrl")
            if (tweetId == null || xUserId == null || xCdnUrl == null) {
                Log.w(TAG, "fetchMediaAssets: skipping malformed doc ${doc.id}")
                return@mapNotNull null
            }
            MediaAssetEntity(
                mediaKey = doc.id,
                tweetId = tweetId,
                xUserId = xUserId,
                xCdnUrl = xCdnUrl,
                localImagePath = null,
                r2BackupUrl = doc.getString("r2BackupUrl"),
                backupAttempts = 0,
                postedAt = doc.getLong("postedAt") ?: 0L,
                createdAt = doc.getLong("createdAt") ?: 0L,
                isFace = doc.getBoolean("isFace"),
                faceConfidence = doc.getDouble("faceConfidence")?.toFloat(),
                faceReviewed = false
            )
        }
    }

    private companion object {
        const val TAG = "FirestoreMirror"
    }
}
