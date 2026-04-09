package com.example.fosterconnect.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "kittens")
data class KittenEntity(
    @PrimaryKey val id: String,
    val externalId: String,
    val name: String,
    val breed: String,
    val color: String,
    val sex: String,
    val isAltered: Boolean,
    val intakeDateMillis: Long,
    val estimatedBirthdayMillis: Long?,
    val weightDeclineWarned: Boolean,
    val isAdopted: Boolean,
    val adoptionDateMillis: Long?
)

@Entity(
    tableName = "weight_entries",
    foreignKeys = [ForeignKey(
        entity = KittenEntity::class,
        parentColumns = ["id"],
        childColumns = ["kittenId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("kittenId")]
)
data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kittenId: String,
    val dateMillis: Long,
    val weightGrams: Float
)

@Entity(
    tableName = "medications",
    foreignKeys = [ForeignKey(
        entity = KittenEntity::class,
        parentColumns = ["id"],
        childColumns = ["kittenId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("kittenId")]
)
data class MedicationEntity(
    @PrimaryKey val id: String,
    val kittenId: String,
    val name: String,
    val strength: String?,
    val instructions: String,
    val startDateMillis: Long,
    val endDateMillis: Long?
)

@Entity(
    tableName = "administered_treatments",
    foreignKeys = [ForeignKey(
        entity = KittenEntity::class,
        parentColumns = ["id"],
        childColumns = ["kittenId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("kittenId")]
)
data class AdministeredTreatmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kittenId: String,
    val treatmentType: String,
    val scheduledDateMillis: Long,
    val administeredDateMillis: Long
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val kittenId: String?,
    val isRead: Boolean
)

data class KittenWithDetails(
    @Embedded val kitten: KittenEntity,
    @Relation(parentColumn = "id", entityColumn = "kittenId")
    val weightEntries: List<WeightEntryEntity>,
    @Relation(parentColumn = "id", entityColumn = "kittenId")
    val medications: List<MedicationEntity>,
    @Relation(parentColumn = "id", entityColumn = "kittenId")
    val administeredTreatments: List<AdministeredTreatmentEntity>
)
