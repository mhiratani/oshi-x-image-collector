package com.hilamalu.oshixcollector.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Xから収集した1枚の画像メタデータ。画像本体は[localImagePath]が指すファイルに保存される。 */
@Entity(
    tableName = "media_assets",
    indices = [
        Index(value = ["xUserId", "postedAt"]),
        Index(value = ["r2BackupUrl"]),
        Index(value = ["isFace"])
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
    val createdAt: Long,
    /** 顔フィルター: null=未判定。[frontend/worker/faceDetect.js]のis_faceと同じ役割。 */
    val isFace: Boolean? = null,
    /** ML Kitは生の確率値を返さないため、検出結果を1f/0fの疑似スコアとして保持する（Web版のBlazeFace confidenceとは値の意味が異なる）。 */
    val faceConfidence: Float? = null,
    /** trueの場合、自動判定（再試行含む）の対象から外す。 */
    val faceReviewed: Boolean = false
)
