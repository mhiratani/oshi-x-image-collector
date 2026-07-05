package com.hilamalu.oshixcollector.data.backup

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import com.hilamalu.oshixcollector.data.db.TargetAccountEntity
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
    private val db by lazy { Firebase.firestore }

    private fun userDocOrNull() = googleAuthManager.currentUser?.uid?.let { uid ->
        if (FirebaseAvailability.isConfigured(context)) db.collection("users").document(uid) else null
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
            db.runBatch { batch ->
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
                            "createdAt" to asset.createdAt
                        )
                    )
                }
            }.await()
        }.onFailure { e -> Log.w(TAG, "mirrorMediaAssets failed (${assets.size} items)", e) }
    }

    private companion object {
        const val TAG = "FirestoreMirror"
    }
}
