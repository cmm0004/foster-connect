package com.example.fosterconnect.foster

import com.example.fosterconnect.history.Message
import com.example.fosterconnect.history.WeightEntry
import com.example.fosterconnect.medication.Medication

enum class Sex(val display: String) {
    MALE("Male"),
    FEMALE("Female")
}

enum class Breed(val display: String) {
    DOMESTIC_SHORT_HAIR("Domestic Short Hair"),
    DOMESTIC_MEDIUM_HAIR("Domestic Medium Hair"),
    DOMESTIC_LONG_HAIR("Domestic Long Hair"),
    SIAMESE("Siamese"),
    MAINE_COON("Maine Coon"),
    PERSIAN("Persian"),
    TABBY("Tabby"),
    CALICO("Calico"),
    TUXEDO("Tuxedo")
}

enum class CoatColor(val display: String) {
    BLACK("Black"),
    WHITE("White"),
    ORANGE("Orange"),
    GRAY("Gray"),
    BROWN("Brown"),
    CALICO("Calico"),
    TORTOISESHELL("Tortoiseshell"),
    GRAY_TABBY("Gray Tabby"),
    ORANGE_TABBY("Orange Tabby"),
    BROWN_TABBY("Brown Tabby"),
    TUXEDO("Tuxedo")
}

data class AdministeredTreatment(
    val treatmentType: String,
    val scheduledDateMillis: Long,
    val administeredDateMillis: Long
)

data class FosterCaseAnimal(
    val animalId: String,
    val fosterCaseId: String,
    val externalId: String = "",
    val name: String,
    val breed: Breed,
    val color: CoatColor,
    val sex: Sex,
    val isAlteredAtIntake: Boolean,
    val intakeDateMillis: Long,
    val estimatedBirthdayMillis: Long? = null,
    val weightEntries: List<WeightEntry> = emptyList(),
    val medications: List<Medication> = emptyList(),
    val administeredTreatments: List<AdministeredTreatment> = emptyList(),
    val messages: List<Message> = emptyList(),
    val weightDeclineWarned: Boolean = false,
    val outDateMillis: Long? = null,
    val isCompleted: Boolean = false
) {
    val ageInWeeks: Int?
        get() {
            val birthday = estimatedBirthdayMillis ?: return null
            val diffMillis = System.currentTimeMillis() - birthday
            return (diffMillis / (7L * 24 * 60 * 60 * 1000)).toInt()
        }
}

data class CompletedFoster(
    val completedRecordId: String,
    val animalId: String,
    val fosterCaseId: String,
    val externalId: String = "",
    val name: String,
    val breed: Breed,
    val color: CoatColor,
    val sex: Sex,
    val estimatedBirthdayMillis: Long? = null,
    val intakeDateMillis: Long,
    val outDateMillis: Long,
    val daysFostered: Int,
    val finalWeightGrams: Float? = null,
    val weightChangeGrams: Float? = null,
    val medicalSummary: String? = null,
    val behaviorSummary: String? = null,
    val placementSummary: String? = null
) {
    val ageInWeeks: Int?
        get() {
            val birthday = estimatedBirthdayMillis ?: return null
            val diffMillis = outDateMillis - birthday
            return (diffMillis / (7L * 24 * 60 * 60 * 1000)).toInt()
        }
}
