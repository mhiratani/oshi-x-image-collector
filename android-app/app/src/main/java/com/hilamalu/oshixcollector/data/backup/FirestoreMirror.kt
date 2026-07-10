package com.hilamalu.oshixcollector.data.backup

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.hilamalu.oshixcollector.data.db.MediaAssetEntity
import com.hilamalu.oshixcollector.data.db.TargetAccountEntity
import com.hilamalu.oshixcollector.data.settings.SecureSettings
import kotlinx.coroutines.tasks.await

/** `users/{uid}/apiUsageLog`の1ドキュメント分（Web版`api_usage_log`と同じ形）。 */
data class ApiUsageEntry(
    val calledAt: Long,
    val purpose: String,
    val endpoint: String,
    val screenName: String?,
    val resource: String,
    val quantity: Int,
    val costUsd: Double
)

/**
 * クラウドバックアップON時のみ使う、Room→Firestoreへの片方向ミラー書き込み。
 * design.md 3.1.2の通り、失敗してもローカルの動作には一切影響させない
 * （呼び出し元はfire-and-forgetで呼び、例外はここで握りつぶしてログのみ残す）。
 * 例外は[mirrorMediaAssetOrThrow]のみ: ユーザー操作1件分の反映は失敗を通知して再操作を促す。
 *
 * コレクション構成: users/{uid}/targetAccounts/{screenName}, users/{uid}/mediaAssets/{mediaKey}
 * （コレクション名はAndroid側の元々の命名、フィールド名はWeb側のsnake_case規約に統一）。
 * セキュリティルールは android-app/firestore.rules 参照。
 *
 * 書き込みは常に merge（[SetOptions.merge]）。Web/Android間で同じフィールドに競合が
 * 起きた場合は「後から書き込んだ方が勝つ」でよい（docs/web-android-user-tree-unification-design.md参照）。
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
        runCatching {
            mirrorTargetAccountOrThrow(account)
        }.onFailure { e -> Log.w(TAG, "mirrorTargetAccount failed: ${account.screenName}", e) }
    }

    /**
     * ユーザー操作（同期停止/再開の切り替え等）1件分のミラー。バッチ系と違い失敗を握りつぶさず
     * 例外を投げる（失敗を無視するとローカルだけ変わり、次回同期でクラウド値に巻き戻るため）。
     */
    suspend fun mirrorTargetAccountOrThrow(account: TargetAccountEntity) {
        val userDoc = userDocOrNull() ?: error("Googleサインインが必要です")
        userDoc.collection("targetAccounts").document(account.screenName)
            .set(
                mapOf(
                    "screen_name" to account.screenName,
                    "x_user_id" to account.xUserId,
                    "last_fetched_id" to account.lastFetchedId,
                    "checked_at" to account.lastCheckedAt?.let { Timestamp(it / 1000, 0) },
                    "created_at" to Timestamp(account.createdAt / 1000, 0),
                    "backfill_cursor" to account.backfillCursor,
                    "backfill_done" to account.backfillDone,
                    "sync_paused" to account.syncPaused
                ),
                SetOptions.merge()
            )
            .await()
    }

    suspend fun mirrorMediaAssets(assets: List<MediaAssetEntity>) {
        if (assets.isEmpty()) return
        val userDoc = userDocOrNull() ?: return
        runCatching {
            userDoc.firestore.runBatch { batch ->
                val collection = userDoc.collection("mediaAssets")
                for (asset in assets) {
                    batch.set(collection.document(asset.mediaKey), assetDocFields(asset), SetOptions.merge())
                }
            }.await()
        }.onFailure { e -> Log.w(TAG, "mirrorMediaAssets failed (${assets.size} items)", e) }
    }

    /**
     * ユーザー操作1件分（お気に入り・顔判定の手動上書き）のミラー。バッチ系と違い失敗を
     * 握りつぶさず例外を投げる。失敗を無視するとローカルだけ変わったままになり、次回同期で
     * クラウド値に巻き戻ってしまうため、呼び出し元がエラー表示して再操作を促す。
     */
    suspend fun mirrorMediaAssetOrThrow(asset: MediaAssetEntity) {
        val userDoc = userDocOrNull() ?: error("Googleサインインが必要です")
        userDoc.collection("mediaAssets").document(asset.mediaKey)
            .set(assetDocFields(asset), SetOptions.merge())
            .await()
    }

    private fun assetDocFields(asset: MediaAssetEntity): Map<String, Any?> = mapOf(
        "media_key" to asset.mediaKey,
        "tweet_id" to asset.tweetId,
        "x_user_id" to asset.xUserId,
        "x_cdn_url" to asset.xCdnUrl,
        "r2_backup_url" to asset.r2BackupUrl,
        "backed_up" to (asset.r2BackupUrl != null),
        "backup_attempts" to asset.backupAttempts,
        "posted_at" to Timestamp(asset.postedAt / 1000, 0),
        "created_at" to Timestamp(asset.createdAt / 1000, 0),
        "is_face" to asset.isFace,
        "face_confidence" to asset.faceConfidence,
        "face_reviewed" to asset.faceReviewed,
        "is_favorite" to asset.isFavorite,
        // Android側にゲーティングUIは無く、保存した画像は常に一覧に出すため常にtrue固定
        "revealed" to true
    )

    /** クラウドバックアップからの復元用。未サインインの場合は例外を投げる（ユーザー起動アクションのため）。 */
    suspend fun fetchTargetAccounts(): List<TargetAccountEntity> {
        val userDoc = userDocOrNull() ?: error("Googleサインインが必要です")
        return userDoc.collection("targetAccounts").get().await().documents.map { doc ->
            TargetAccountEntity(
                screenName = doc.id,
                xUserId = doc.getString("x_user_id"),
                lastFetchedId = doc.getString("last_fetched_id"),
                lastCheckedAt = doc.getTimestamp("checked_at")?.toDate()?.time,
                createdAt = doc.getTimestamp("created_at")?.toDate()?.time ?: 0L,
                backfillCursor = doc.getString("backfill_cursor"),
                backfillDone = doc.getBoolean("backfill_done") ?: false,
                syncPaused = doc.getBoolean("sync_paused") ?: false
            )
        }
    }

    /** クラウドバックアップからの復元用。未サインインの場合は例外を投げる（ユーザー起動アクションのため）。 */
    suspend fun fetchMediaAssets(): List<MediaAssetEntity> {
        val userDoc = userDocOrNull() ?: error("Googleサインインが必要です")
        return userDoc.collection("mediaAssets").get().await().documents.mapNotNull { doc ->
            val tweetId = doc.getString("tweet_id")
            val xUserId = doc.getString("x_user_id")
            val xCdnUrl = doc.getString("x_cdn_url")
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
                r2BackupUrl = doc.getString("r2_backup_url"),
                backupAttempts = (doc.getLong("backup_attempts") ?: 0L).toInt(),
                postedAt = doc.getTimestamp("posted_at")?.toDate()?.time ?: 0L,
                createdAt = doc.getTimestamp("created_at")?.toDate()?.time ?: 0L,
                isFace = doc.getBoolean("is_face"),
                faceConfidence = doc.getDouble("face_confidence")?.toFloat(),
                faceReviewed = doc.getBoolean("face_reviewed") ?: false,
                isFavorite = doc.getBoolean("is_favorite") ?: false
            )
        }
    }

    /**
     * X APIの呼び出し1回分の使用量ログを記録する（Web版`api_usage_log`と同じ形）。
     * 同じGoogleアカウントの使用料としてWeb版と合算表示するため`users/{uid}/apiUsageLog`に追加する。
     * 未サインインの場合は静かにスキップする（バックアップ機能全体と同じ挙動、この機能のためだけに
     * サインインを強制しない）。失敗しても本処理（X API呼び出し自体）には影響させない。
     */
    suspend fun logApiUsage(
        purpose: String,
        endpoint: String,
        screenName: String?,
        resource: String,
        quantity: Int
    ) {
        val userDoc = userDocOrNull() ?: return
        runCatching {
            val unitCostUsd = UNIT_COST_USD[resource] ?: 0.0
            userDoc.collection("apiUsageLog").add(
                mapOf(
                    "called_at" to Timestamp.now(),
                    "purpose" to purpose,
                    "endpoint" to endpoint,
                    "screen_name" to screenName,
                    "resource" to resource,
                    "quantity" to quantity,
                    "unit_cost_usd" to unitCostUsd,
                    "cost_usd" to quantity * unitCostUsd
                )
            ).await()
        }.onFailure { e -> Log.w(TAG, "logApiUsage failed: $purpose/$endpoint", e) }
    }

    /**
     * API使用量画面用。Web版`/api/usage`と同じ`users/{uid}/apiUsageLog`を読むため、
     * 同じGoogleアカウントならWeb/Android両方の呼び出しが合算されて見える。
     * 未サインインの場合は例外を投げる（ユーザー起動アクションのため）。
     */
    suspend fun fetchApiUsageLog(): List<ApiUsageEntry> {
        val userDoc = userDocOrNull() ?: error("Googleサインインが必要です")
        return userDoc.collection("apiUsageLog")
            .orderBy("called_at", Query.Direction.DESCENDING)
            .get().await().documents.mapNotNull { doc ->
                val calledAt = doc.getTimestamp("called_at")?.toDate()?.time ?: return@mapNotNull null
                ApiUsageEntry(
                    calledAt = calledAt,
                    purpose = doc.getString("purpose") ?: "unknown",
                    endpoint = doc.getString("endpoint") ?: "",
                    screenName = doc.getString("screen_name"),
                    resource = doc.getString("resource") ?: "",
                    quantity = (doc.getLong("quantity") ?: 0L).toInt(),
                    costUsd = doc.getDouble("cost_usd") ?: 0.0
                )
            }
    }

    private companion object {
        const val TAG = "FirestoreMirror"

        // X APIは従量課金（返却リソース件数に応じた課金）。単価はWeb版のdocker-compose.yml
        // (UNIT_COST_USD_USER_READ/UNIT_COST_USD_POSTS_READ)と同じ値を持たせる。
        val UNIT_COST_USD = mapOf(
            "user_read" to 0.01,
            "posts_read" to 0.005
        )
    }
}
