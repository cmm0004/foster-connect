package com.example.fosterconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncDao {

    @Query("SELECT * FROM animals")
    suspend fun getAllAnimals(): List<AnimalEntity>

    @Query("SELECT * FROM foster_cases")
    suspend fun getAllFosterCases(): List<FosterCaseEntity>

    @Query("SELECT * FROM case_weights")
    suspend fun getAllWeights(): List<CaseWeightEntity>

    @Query("SELECT * FROM case_stools")
    suspend fun getAllStools(): List<CaseStoolEntity>

    @Query("SELECT * FROM case_events")
    suspend fun getAllEvents(): List<CaseEventEntity>

    @Query("SELECT * FROM case_treatments")
    suspend fun getAllTreatments(): List<CaseTreatmentEntity>

    @Query("SELECT * FROM case_medications")
    suspend fun getAllMedications(): List<CaseMedicationEntity>

    @Query("SELECT * FROM case_messages")
    suspend fun getAllMessages(): List<CaseMessageEntity>

    @Query("SELECT * FROM completed_foster_records")
    suspend fun getAllCompletedRecords(): List<CompletedFosterRecordEntity>

    @Query("SELECT * FROM assigned_traits")
    suspend fun getAllTraits(): List<AssignedTraitEntity>

    @Query("SELECT syncId FROM case_weights WHERE syncId != ''")
    suspend fun getAllWeightSyncIds(): List<String>

    @Query("SELECT syncId FROM case_stools WHERE syncId != ''")
    suspend fun getAllStoolSyncIds(): List<String>

    @Query("SELECT syncId FROM case_events WHERE syncId != ''")
    suspend fun getAllEventSyncIds(): List<String>

    @Query("SELECT syncId FROM case_treatments WHERE syncId != ''")
    suspend fun getAllTreatmentSyncIds(): List<String>

    @Query("SELECT * FROM case_treatments WHERE syncId = :syncId LIMIT 1")
    suspend fun getTreatmentBySyncId(syncId: String): CaseTreatmentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWeightIfNew(weight: CaseWeightEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTraitIgnore(trait: AssignedTraitEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStoolIfNew(stool: CaseStoolEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventIfNew(event: CaseEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTreatmentIfNew(treatment: CaseTreatmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnimal(animal: AnimalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFosterCase(fosterCase: FosterCaseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedication(medication: CaseMedicationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIgnore(message: CaseMessageEntity)

    @Query("UPDATE case_messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markMessageReadById(messageId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCompletedRecord(record: CompletedFosterRecordEntity)

    @Query("UPDATE case_treatments SET administeredDateMillis = :administeredDateMillis, doseGiven = :doseGiven WHERE id = :treatmentId")
    suspend fun updateTreatmentAdministered(treatmentId: Long, administeredDateMillis: Long, doseGiven: String?)
}
