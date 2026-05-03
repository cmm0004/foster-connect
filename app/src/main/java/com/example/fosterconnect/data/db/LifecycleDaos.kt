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

    @Query("UPDATE animals SET litterName = :litterName, updatedAtMillis = :updatedAtMillis WHERE id = :animalId")
    suspend fun updateLitterName(animalId: String, litterName: String?, updatedAtMillis: Long)

    @Query("UPDATE animals SET name = :name, updatedAtMillis = :updatedAtMillis WHERE id = :animalId")
    suspend fun updateName(animalId: String, name: String, updatedAtMillis: Long)
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

    @Query("UPDATE foster_cases SET collarColor = :collarColor, updatedAtMillis = :updatedAtMillis WHERE id = :caseId")
    suspend fun updateCollarColor(caseId: String, collarColor: String?, updatedAtMillis: Long)

    @Query("UPDATE foster_cases SET weightDeclineWarned = :warned, updatedAtMillis = :updatedAtMillis WHERE id = :caseId")
    suspend fun updateWeightDeclineWarned(caseId: String, warned: Boolean, updatedAtMillis: Long)

    @Query("UPDATE foster_cases SET nextVaccineDateMillis = :dateMillis, updatedAtMillis = :updatedAtMillis WHERE id = :caseId")
    suspend fun updateNextVaccineDate(caseId: String, dateMillis: Long, updatedAtMillis: Long)

    @Query("UPDATE foster_cases SET nextVaccineDateMillis = NULL, updatedAtMillis = :updatedAtMillis WHERE id = :caseId")
    suspend fun clearNextVaccineDate(caseId: String, updatedAtMillis: Long)

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
    suspend fun insertStool(stool: CaseStoolEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedication(medication: CaseMedicationEntity)

    @Query(
        "UPDATE case_medications " +
            "SET endDateMillis = :endDateMillis, isActive = 0, updatedAtMillis = :endDateMillis WHERE id = :medicationId"
    )
    suspend fun stopMedication(medicationId: String, endDateMillis: Long)

    @Query(
        "UPDATE case_medications " +
            "SET endDateMillis = :endDateMillis, isActive = 0, updatedAtMillis = :endDateMillis " +
            "WHERE fosterCaseId = :caseId AND isActive = 1"
    )
    suspend fun stopAllActiveMedications(caseId: String, endDateMillis: Long)

    @Query("DELETE FROM case_treatments WHERE fosterCaseId = :caseId AND administeredDateMillis IS NULL")
    suspend fun deleteAllUnadministeredTreatments(caseId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: CasePhotoEntity)

    @Query("DELETE FROM case_photos WHERE id = :id")
    suspend fun deletePhoto(id: String)

    @Query("SELECT * FROM case_photos WHERE fosterCaseId = :caseId ORDER BY addedAtMillis")
    suspend fun photosFor(caseId: String): List<CasePhotoEntity>

    @Query("SELECT COUNT(*) FROM case_photos WHERE fosterCaseId = :caseId")
    suspend fun photoCountFor(caseId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: CaseEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTreatment(treatment: CaseTreatmentEntity)

    @Query("SELECT COUNT(*) FROM case_treatments WHERE fosterCaseId = :caseId AND scheduledDateMillis = :scheduledDateMillis AND administeredDateMillis IS NOT NULL")
    suspend fun countAdministeredTreatmentsOnDate(caseId: String, scheduledDateMillis: Long): Int

    @Query("DELETE FROM case_treatments WHERE fosterCaseId = :caseId AND scheduledDateMillis = :scheduledDateMillis AND administeredDateMillis IS NULL")
    suspend fun deleteScheduledTreatments(caseId: String, scheduledDateMillis: Long)

    @Query("UPDATE case_treatments SET administeredDateMillis = :administeredDateMillis, doseGiven = :doseGiven WHERE id = :treatmentId")
    suspend fun markTreatmentAdministered(treatmentId: Long, administeredDateMillis: Long, doseGiven: String?)

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

    @Query("DELETE FROM completed_foster_records WHERE fosterCaseId = :caseId")
    suspend fun deleteCompletedRecord(caseId: String)

    @Query(
        "UPDATE foster_cases " +
            "SET status = :status, outDateMillis = NULL, updatedAtMillis = :updatedAtMillis " +
            "WHERE id = :caseId"
    )
    suspend fun reopenCase(caseId: String, status: String, updatedAtMillis: Long)

    @Transaction
    @Query("SELECT * FROM completed_foster_records ORDER BY outDateMillis DESC")
    fun observeCompletedRecords(): Flow<List<CompletedFosterRecordWithAnimal>>
}

@Dao
interface TraitDao {

    @Query("SELECT * FROM assigned_traits WHERE animalId = :animalId AND fosterCaseId = :fosterCaseId ORDER BY assignedAtMillis")
    fun observeTraitsForCase(animalId: String, fosterCaseId: String): Flow<List<AssignedTraitEntity>>

    @Query("SELECT * FROM assigned_traits WHERE fosterCaseId = :fosterCaseId ORDER BY assignedAtMillis")
    suspend fun getTraitsForCase(fosterCaseId: String): List<AssignedTraitEntity>

    @Query("SELECT fosterCaseId, SUM(score) as totalScore FROM assigned_traits WHERE fosterCaseId IS NOT NULL GROUP BY fosterCaseId")
    fun observeAllCaseScores(): Flow<List<CaseTraitScore>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrait(trait: AssignedTraitEntity)

    @Query("DELETE FROM assigned_traits WHERE animalId = :animalId AND fosterCaseId = :fosterCaseId AND traitName = :traitName")
    suspend fun removeTrait(animalId: String, fosterCaseId: String, traitName: String)

    @Query("DELETE FROM assigned_traits WHERE animalId = :animalId AND fosterCaseId = :fosterCaseId AND traitName IN (:traitNames)")
    suspend fun removeTraits(animalId: String, fosterCaseId: String, traitNames: List<String>)
}
