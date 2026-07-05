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
    val createdAt: Long
)
