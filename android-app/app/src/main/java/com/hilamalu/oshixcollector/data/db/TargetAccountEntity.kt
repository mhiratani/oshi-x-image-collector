package com.hilamalu.oshixcollector.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 追跡対象のXアカウント（`@`なしのscreen_name をキーにする）。 */
@Entity(tableName = "target_accounts")
data class TargetAccountEntity(
    @PrimaryKey val screenName: String,
    val xUserId: String?,
    val lastFetchedId: String?,
    val lastCheckedAt: Long?,
    val createdAt: Long,
    /** バックフィル（過去方向の遡り取得）の進捗カーソル。Web/Android共有の進捗マーカー。 */
    val backfillCursor: String? = null,
    /** trueになったら、これ以上遡る投稿が無い（またはAPI上限到達）ため以降バックフィルを行わない。 */
    val backfillDone: Boolean = false,
    /**
     * 同期停止。trueの間は新着取得・バックフィルの対象から外す（収集済みデータは残す）。
     * このアプリに「アカウント削除」の概念は無く、追跡をやめたい場合はこのフラグで停止する。
     * Web/Android共有フィールド（Firestoreの`sync_paused`）。
     */
    val syncPaused: Boolean = false
)


