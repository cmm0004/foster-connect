package com.example.fosterconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KittenDao {

    @Transaction
    @Query("SELECT * FROM kittens")
    fun observeAllWithDetails(): Flow<List<KittenWithDetails>>

    @Query("SELECT COUNT(*) FROM kittens")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKitten(kitten: KittenEntity)

    @Update
    suspend fun updateKitten(kitten: KittenEntity)

    @Query("UPDATE kittens SET estimatedBirthdayMillis = :birthday WHERE id = :kittenId")
    suspend fun updateBirthday(kittenId: String, birthday: Long)

    @Query("UPDATE kittens SET externalId = :externalId WHERE id = :kittenId")
    suspend fun updateExternalId(kittenId: String, externalId: String)

    @Query("UPDATE kittens SET weightDeclineWarned = :warned WHERE id = :kittenId")
    suspend fun updateWeightDeclineWarned(kittenId: String, warned: Boolean)

    @Query("UPDATE kittens SET isAdopted = 1, adoptionDateMillis = :adoptionDate WHERE id = :kittenId")
    suspend fun markAdopted(kittenId: String, adoptionDate: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeightEntry(entry: WeightEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedication(medication: MedicationEntity)

    @Query("UPDATE medications SET endDateMillis = :endDate WHERE id = :medicationId")
    suspend fun stopMedication(medicationId: String, endDate: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdministeredTreatment(treatment: AdministeredTreatmentEntity)
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markRead(messageId: String)
}
