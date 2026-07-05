package com.hilamalu.oshixcollector.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TargetAccountEntity::class, MediaAssetEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetAccountDao(): TargetAccountDao
    abstract fun mediaAssetDao(): MediaAssetDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "oshi_x_image_collector.db"
                ).build().also { instance = it }
            }
    }
}
