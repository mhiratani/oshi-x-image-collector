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

    @Query("DELETE FROM media_assets WHERE xUserId = :xUserId")
    suspend fun deleteByAccount(xUserId: String)
}
