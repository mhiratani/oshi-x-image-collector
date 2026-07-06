package com.hilamalu.oshixcollector.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetAccountDao {
    @Query("SELECT * FROM target_accounts ORDER BY createdAt")
    fun observeAll(): Flow<List<TargetAccountEntity>>

    @Query("SELECT * FROM target_accounts ORDER BY createdAt")
    suspend fun getAll(): List<TargetAccountEntity>

    @Query("SELECT * FROM target_accounts WHERE screenName = :screenName")
    suspend fun getByScreenName(screenName: String): TargetAccountEntity?

    @Query("SELECT COUNT(*) FROM target_accounts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: TargetAccountEntity)

    @Update
    suspend fun update(account: TargetAccountEntity)

    /**
     * クラウド同期(pull)用。全フィールドがクラウド由来でローカル専有フィールドが無いため、
     * 既存行があれば単純に上書きしてよい。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: TargetAccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<TargetAccountEntity>)


    @Delete
    suspend fun delete(account: TargetAccountEntity)

    @Query("DELETE FROM target_accounts WHERE screenName = :screenName")
    suspend fun deleteByScreenName(screenName: String)
}
