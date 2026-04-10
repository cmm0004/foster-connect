package com.example.fosterconnect.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        KittenEntity::class,
        WeightEntryEntity::class,
        MedicationEntity::class,
        AdministeredTreatmentEntity::class,
        MessageEntity::class,
        RankFacetEntity::class,
        KittenRankScoreEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun kittenDao(): KittenDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rank_facets` (" +
                        "`id` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`description` TEXT, " +
                        "`sortOrder` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `kitten_rank_scores` (" +
                        "`kittenId` TEXT NOT NULL, " +
                        "`facetId` TEXT NOT NULL, " +
                        "`score` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`kittenId`, `facetId`), " +
                        "FOREIGN KEY(`kittenId`) REFERENCES `kittens`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`facetId`) REFERENCES `rank_facets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_kitten_rank_scores_kittenId` ON `kitten_rank_scores` (`kittenId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_kitten_rank_scores_facetId` ON `kitten_rank_scores` (`facetId`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "foster_connect.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
