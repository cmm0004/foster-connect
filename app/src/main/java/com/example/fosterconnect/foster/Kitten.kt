package com.example.fosterconnect.foster

import androidx.annotation.DrawableRes
import com.example.fosterconnect.R
import com.example.fosterconnect.history.Message
import com.example.fosterconnect.history.WeightEntry
import com.example.fosterconnect.medication.Medication

@DrawableRes
fun CoatColor.defaultProfileDrawable(): Int = when (this) {
    CoatColor.BLACK -> R.drawable.black
    CoatColor.WHITE -> R.drawable.white
    CoatColor.GRAY_TABBY -> R.drawable.greytabby
    CoatColor.DILUTED -> R.drawable.dilutedtabby
    CoatColor.CALICO -> R.drawable.calico
    CoatColor.TORTOISESHELL -> R.drawable.tortoiseshell
    CoatColor.TUXEDO -> R.drawable.tuxedo
    CoatColor.FLAMEPOINT -> R.drawable.chocolateflamepoint
    CoatColor.ORANGE,
    CoatColor.ORANGE_TABBY -> R.drawable.default_kitten_profile
}

enum class Sex(val display: String) {
    MALE("Male"),
    FEMALE("Female")
}

enum class Breed(val display: String) {
    DOMESTIC_SHORT_HAIR("Domestic Short Hair"),
    DOMESTIC_MEDIUM_HAIR("Domestic Medium Hair"),
    DOMESTIC_LONG_HAIR("Domestic Long Hair"),
}

enum class CoatColor(val display: String) {
    BLACK("Black"),
    WHITE("White"),
    ORANGE("Orange"),
    GRAY_TABBY("Gray Tabby"),
    DILUTED("Diluted"),
    CALICO("Calico"),
    TORTOISESHELL("Tortoiseshell"),
    ORANGE_TABBY("Orange Tabby"),
    TUXEDO("Tuxedo"),
    FLAMEPOINT("Flamepoint")
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
