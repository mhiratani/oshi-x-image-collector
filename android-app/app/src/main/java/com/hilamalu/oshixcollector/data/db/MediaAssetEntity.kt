package com.hilamalu.oshixcollector.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Xから収集した1枚の画像メタデータ。画像本体は[localImagePath]が指すファイルに保存される。 */
@Entity(
    tableName = "media_assets",
    indices = [
        Index(value = ["xUserId", "postedAt"]),
        Index(value = ["r2BackupUrl"])
    ]
)
data class MediaAssetEntity(
    @PrimaryKey val mediaKey: String,
    val tweetId: String,
    val xUserId: String,
    val xCdnUrl: String,
    val localImagePath: String?,
    val r2BackupUrl: String?,
    val backupAttempts: Int = 0,
    val postedAt: Long,
    val createdAt: Long
)
