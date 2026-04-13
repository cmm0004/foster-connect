package com.example.fosterconnect.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class AnimalSpecies {
    CAT,
    DOG,
    OTHER
}

enum class FosterCaseStatus {
    ACTIVE,
    COMPLETED
}

@Entity(
    tableName = "animals",
    indices = [Index(value = ["externalId"], unique = true)]
)
data class AnimalEntity(
    @PrimaryKey val id: String,
    val externalId: String?,
    val name: String,
    val species: String,
    val breed: String,
    val color: String,
    val sex: String,
    val estimatedBirthdayMillis: Long?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "foster_cases",
    foreignKeys = [
        ForeignKey(
            entity = AnimalEntity::class,
            parentColumns = ["id"],
            childColumns = ["animalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("animalId"),
        Index(value = ["animalId", "status"])
    ]
)
data class FosterCaseEntity(
    @PrimaryKey val id: String,
    val animalId: String,
    val status: String,
    val intakeDateMillis: Long,
    val outDateMillis: Long?,
    val isAlteredAtIntake: Boolean,
    val weightDeclineWarned: Boolean,
    val notes: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "case_weights",
    foreignKeys = [
        ForeignKey(
            entity = FosterCaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["fosterCaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["fosterCaseId", "dateMillis"])]
)
data class CaseWeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fosterCaseId: String,
    val dateMillis: Long,
    val weightGrams: Float
)

@Entity(
    tableName = "case_medications",
    foreignKeys = [
        ForeignKey(
            entity = FosterCaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["fosterCaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("fosterCaseId")]
)
data class CaseMedicationEntity(
    @PrimaryKey val id: String,
    val fosterCaseId: String,
    val name: String,
    val strength: String?,
    val instructions: String,
    val startDateMillis: Long,
    val endDateMillis: Long?,
    val isActive: Boolean
)

@Entity(
    tableName = "case_treatments",
    foreignKeys = [
        ForeignKey(
            entity = FosterCaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["fosterCaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("fosterCaseId")]
)
data class CaseTreatmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fosterCaseId: String,
    val treatmentType: String,
    val scheduledDateMillis: Long,
    val administeredDateMillis: Long,
    val notes: String?
)

@Entity(
    tableName = "case_messages",
    foreignKeys = [
        ForeignKey(
            entity = FosterCaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["fosterCaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["fosterCaseId", "timestamp"])]
)
data class CaseMessageEntity(
    @PrimaryKey val id: String,
    val fosterCaseId: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val isRead: Boolean
)

@Entity(
    tableName = "completed_foster_records",
    foreignKeys = [
        ForeignKey(
            entity = AnimalEntity::class,
            parentColumns = ["id"],
            childColumns = ["animalId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FosterCaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["fosterCaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("animalId"),
        Index(value = ["fosterCaseId"], unique = true),
        Index(value = ["animalId", "outDateMillis"])
    ]
)
data class CompletedFosterRecordEntity(
    @PrimaryKey val id: String,
    val animalId: String,
    val fosterCaseId: String,
    val intakeDateMillis: Long,
    val outDateMillis: Long,
    val daysFostered: Int,
    val finalWeightGrams: Float?,
    val weightChangeGrams: Float?,
    val medicalSummary: String?,
    val behaviorSummary: String?,
    val placementSummary: String?,
    val createdAtMillis: Long
)

@Entity(tableName = "rank_facets")
data class RankFacetEntityV2(
    @PrimaryKey val id: String,
    val displayName: String,
    val description: String? = null,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "ranking_records",
    foreignKeys = [
        ForeignKey(
            entity = AnimalEntity::class,
            parentColumns = ["id"],
            childColumns = ["animalId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FosterCaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["fosterCaseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("animalId"),
        Index("fosterCaseId")
    ]
)
data class RankingRecordEntity(
    @PrimaryKey val id: String,
    val animalId: String,
    val fosterCaseId: String?,
    val rankedAtMillis: Long,
    val rankVersion: Int = 1,
    val notes: String?
)

@Entity(
    tableName = "ranking_scores",
    primaryKeys = ["rankingRecordId", "facetId"],
    foreignKeys = [
        ForeignKey(
            entity = RankingRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["rankingRecordId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RankFacetEntityV2::class,
            parentColumns = ["id"],
            childColumns = ["facetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("rankingRecordId"), Index("facetId")]
)
data class RankingScoreEntity(
    val rankingRecordId: String,
    val facetId: String,
    val score: Int
)

data class AnimalWithCases(
    @Embedded val animal: AnimalEntity,
    @Relation(parentColumn = "id", entityColumn = "animalId")
    val fosterCases: List<FosterCaseEntity>
)

data class FosterCaseWithDetails(
    @Embedded val fosterCase: FosterCaseEntity,
    @Relation(parentColumn = "id", entityColumn = "fosterCaseId")
    val weights: List<CaseWeightEntity>,
    @Relation(parentColumn = "id", entityColumn = "fosterCaseId")
    val medications: List<CaseMedicationEntity>,
    @Relation(parentColumn = "id", entityColumn = "fosterCaseId")
    val treatments: List<CaseTreatmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "fosterCaseId")
    val messages: List<CaseMessageEntity>
)

data class CompletedFosterRecordWithAnimal(
    @Embedded val completedRecord: CompletedFosterRecordEntity,
    @Relation(parentColumn = "animalId", entityColumn = "id")
    val animal: AnimalEntity
)

data class RankingRecordWithScores(
    @Embedded val rankingRecord: RankingRecordEntity,
    @Relation(parentColumn = "id", entityColumn = "rankingRecordId")
    val scores: List<RankingScoreEntity>
)
