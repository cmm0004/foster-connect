package com.example.fosterconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimalDao {

    @Query("SELECT * FROM animals ORDER BY name")
    fun observeAnimals(): Flow<List<AnimalEntity>>

    @Query("SELECT COUNT(*) FROM animals")
    suspend fun countAnimals(): Int

    @Transaction
    @Query("SELECT * FROM animals ORDER BY name")
    fun observeAnimalsWithCases(): Flow<List<AnimalWithCases>>

    @Query("SELECT * FROM animals WHERE id = :animalId")
    suspend fun getAnimal(animalId: String): AnimalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnimal(animal: AnimalEntity)

    @Update
    suspend fun updateAnimal(animal: AnimalEntity)

    @Query("UPDATE animals SET estimatedBirthdayMillis = :birthdayMillis, updatedAtMillis = :updatedAtMillis WHERE id = :animalId")
    suspend fun updateBirthday(animalId: String, birthdayMillis: Long, updatedAtMillis: Long)

    @Query("UPDATE animals SET externalId = :externalId, updatedAtMillis = :updatedAtMillis WHERE id = :animalId")
    suspend fun updateExternalId(animalId: String, externalId: String, updatedAtMillis: Long)
}

@Dao
interface FosterCaseDao {

    @Transaction
    @Query("SELECT * FROM foster_cases WHERE status = 'ACTIVE' ORDER BY intakeDateMillis DESC")
    fun observeActiveCasesWithDetails(): Flow<List<FosterCaseWithDetails>>

    @Transaction
    @Query("SELECT * FROM foster_cases ORDER BY intakeDateMillis DESC")
    fun observeAllCasesWithDetails(): Flow<List<FosterCaseWithDetails>>

    @Transaction
    @Query("SELECT * FROM foster_cases WHERE id = :caseId")
    suspend fun getCaseWithDetails(caseId: String): FosterCaseWithDetails?

    @Query("SELECT * FROM foster_cases WHERE animalId = :animalId ORDER BY intakeDateMillis DESC")
    fun observeCasesForAnimal(animalId: String): Flow<List<FosterCaseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCase(fosterCase: FosterCaseEntity)

    @Update
    suspend fun updateCase(fosterCase: FosterCaseEntity)

    @Query("UPDATE foster_cases SET weightDeclineWarned = :warned, updatedAtMillis = :updatedAtMillis WHERE id = :caseId")
    suspend fun updateWeightDeclineWarned(caseId: String, warned: Boolean, updatedAtMillis: Long)

    @Query(
        "UPDATE foster_cases " +
            "SET status = :status, outDateMillis = :outDateMillis, updatedAtMillis = :updatedAtMillis " +
            "WHERE id = :caseId"
    )
    suspend fun closeCase(
        caseId: String,
        status: String,
        outDateMillis: Long,
        updatedAtMillis: Long
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeight(weight: CaseWeightEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedication(medication: CaseMedicationEntity)

    @Query(
        "UPDATE case_medications " +
            "SET endDateMillis = :endDateMillis, isActive = 0 WHERE id = :medicationId"
    )
    suspend fun stopMedication(medicationId: String, endDateMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTreatment(treatment: CaseTreatmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: CaseMessageEntity)

    @Query("UPDATE case_messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markMessageRead(messageId: String)

    @Query("SELECT * FROM case_messages WHERE fosterCaseId = :caseId ORDER BY timestamp DESC")
    fun observeMessagesForCase(caseId: String): Flow<List<CaseMessageEntity>>

    @Query("SELECT * FROM case_messages ORDER BY timestamp DESC")
    fun observeAllMessages(): Flow<List<CaseMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompletedRecord(record: CompletedFosterRecordEntity)

    @Transaction
    @Query("SELECT * FROM completed_foster_records ORDER BY outDateMillis DESC")
    fun observeCompletedRecords(): Flow<List<CompletedFosterRecordWithAnimal>>
}

@Dao
interface RankingDao {

    @Query("SELECT * FROM rank_facets ORDER BY sortOrder, displayName")
    fun observeRankFacets(): Flow<List<RankFacetEntityV2>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRankFacet(facet: RankFacetEntityV2)

    @Query("SELECT COUNT(*) FROM rank_facets")
    suspend fun rankFacetCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRankingRecord(record: RankingRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRankingScores(scores: List<RankingScoreEntity>)

    @Transaction
    @Query("SELECT * FROM ranking_records WHERE animalId = :animalId ORDER BY rankedAtMillis DESC")
    fun observeRankingRecordsForAnimal(animalId: String): Flow<List<RankingRecordWithScores>>

    @Transaction
    @Query("SELECT * FROM ranking_records ORDER BY rankedAtMillis DESC")
    fun observeAllRankingRecords(): Flow<List<RankingRecordWithScores>>

    @Query(
        "SELECT rs.facetId, AVG(CAST(rs.score AS REAL)) AS averageScore " +
            "FROM ranking_scores rs " +
            "INNER JOIN ranking_records rr ON rr.id = rs.rankingRecordId " +
            "GROUP BY rs.facetId"
    )
    fun observeFacetAverages(): Flow<List<RankingFacetAverage>>
}

data class RankingFacetAverage(
    val facetId: String,
    val averageScore: Double
)
