package com.example.fosterconnect.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AnimalEntity::class,
        FosterCaseEntity::class,
        CaseWeightEntity::class,
        CaseMedicationEntity::class,
        CaseTreatmentEntity::class,
        CaseMessageEntity::class,
        CompletedFosterRecordEntity::class,
        RankFacetEntityV2::class,
        RankingRecordEntity::class,
        RankingScoreEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun animalDao(): AnimalDao
    abstract fun fosterCaseDao(): FosterCaseDao
    abstract fun rankingDao(): RankingDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "foster_connect.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
