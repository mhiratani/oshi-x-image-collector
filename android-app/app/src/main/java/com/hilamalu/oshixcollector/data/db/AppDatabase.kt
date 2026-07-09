package com.hilamalu.oshixcollector.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TargetAccountEntity::class, MediaAssetEntity::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetAccountDao(): TargetAccountDao
    abstract fun mediaAssetDao(): MediaAssetDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v2→v3: バックフィル機能のための2カラム追加。 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE target_accounts ADD COLUMN backfillCursor TEXT")
                db.execSQL("ALTER TABLE target_accounts ADD COLUMN backfillDone INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3→v4: お気に入り機能のための1カラム追加。 */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_assets ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "oshi_x_image_collector.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // v1→v2は未リリース期間のため実移行を書かず単純に作り直す。
                    // v2→v3以降はMIGRATION_2_3のように正式なMigrationを書くこと。
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}


