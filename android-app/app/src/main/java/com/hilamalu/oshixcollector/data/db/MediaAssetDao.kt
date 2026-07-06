package com.hilamalu.oshixcollector.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaAssetDao {
    @Query("SELECT * FROM media_assets ORDER BY postedAt DESC, mediaKey DESC")
    fun observeAll(): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE xUserId = :xUserId ORDER BY postedAt DESC, mediaKey DESC")
    fun observeByAccount(xUserId: String): Flow<List<MediaAssetEntity>>

    /** [frontend/worker/batch.js]の insertMedia (`ON CONFLICT (media_key) DO NOTHING`) と同じ挙動。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(assets: List<MediaAssetEntity>): List<Long>

    @Query("UPDATE media_assets SET localImagePath = :localImagePath WHERE mediaKey = :mediaKey")
    suspend fun updateLocalImagePath(mediaKey: String, localImagePath: String)

    @Query("UPDATE media_assets SET r2BackupUrl = :r2BackupUrl WHERE mediaKey = :mediaKey")
    suspend fun updateR2BackupUrl(mediaKey: String, r2BackupUrl: String)

    @Query("UPDATE media_assets SET backupAttempts = backupAttempts + 1 WHERE mediaKey = :mediaKey")
    suspend fun incrementBackupAttempts(mediaKey: String)

    @Query("SELECT * FROM media_assets WHERE r2BackupUrl IS NULL AND backupAttempts < :maxAttempts ORDER BY postedAt DESC")
    suspend fun getPendingBackup(maxAttempts: Int = 5): List<MediaAssetEntity>

    /** クラウドバックアップからの復元用。R2にバックアップ済みのはずの行のうち、ローカル画像が欠けているものを探す。 */
    @Query("SELECT * FROM media_assets WHERE r2BackupUrl IS NOT NULL")
    suspend fun getBackedUp(): List<MediaAssetEntity>

    /** [frontend/worker/faceDetect.js]の対象条件（`is_face IS NULL AND NOT face_reviewed`）と同じ。 */
    @Query("SELECT * FROM media_assets WHERE isFace IS NULL AND NOT faceReviewed AND localImagePath IS NOT NULL ORDER BY postedAt DESC")
    suspend fun getPendingFaceDetection(): List<MediaAssetEntity>

    @Query("UPDATE media_assets SET isFace = :isFace, faceConfidence = :faceConfidence WHERE mediaKey = :mediaKey")
    suspend fun updateFaceResult(mediaKey: String, isFace: Boolean, faceConfidence: Float)

    @Query("DELETE FROM media_assets WHERE xUserId = :xUserId")
    suspend fun deleteByAccount(xUserId: String)

    /**
     * クラウド同期(pull)用。ローカル専有フィールド（[MediaAssetEntity.localImagePath],
     * [MediaAssetEntity.backupAttempts]）は保持したまま、クラウド由来フィールドだけを上書きする。
     * 対象行がローカルに無い場合は何もしない（新規行は呼び出し側で[insertAll]すること）。
     */
    @Query(
        """
        UPDATE media_assets SET
            r2BackupUrl = :r2BackupUrl,
            isFace = :isFace,
            faceConfidence = :faceConfidence,
            faceReviewed = :faceReviewed
        WHERE mediaKey = :mediaKey
        """
    )
    suspend fun updateCloudFields(
        mediaKey: String,
        r2BackupUrl: String?,
        isFace: Boolean?,
        faceConfidence: Float?,
        faceReviewed: Boolean
    )

    @Query("SELECT mediaKey FROM media_assets WHERE mediaKey IN (:mediaKeys)")
    suspend fun getExistingMediaKeys(mediaKeys: List<String>): List<String>

    /** バックフィル起点解決用（Web版の getOldestTweetId 相当）: 保存済みの中で最も古いツイートID。 */
    @Query("SELECT tweetId FROM media_assets WHERE xUserId = :xUserId ORDER BY postedAt ASC LIMIT 1")
    suspend fun getOldestTweetId(xUserId: String): String?
}


