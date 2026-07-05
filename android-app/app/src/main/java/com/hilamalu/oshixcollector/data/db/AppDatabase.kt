package com.hilamalu.oshixcollector.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TargetAccountEntity::class, MediaAssetEntity::class],
    version = 2,
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
                )
                    // アプリはまだ未リリースのため、v1→v2の実移行は書かず単純に作り直す。
                    // リリース後にスキーマを変える場合は正式なMigrationに置き換えること。
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
