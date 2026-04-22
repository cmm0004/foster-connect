package com.example.fosterconnect.foster

import androidx.annotation.DrawableRes
import com.example.fosterconnect.R
import com.example.fosterconnect.history.EventEntry
import com.example.fosterconnect.history.Message
import com.example.fosterconnect.history.StoolEntry
import com.example.fosterconnect.history.WeightEntry
import com.example.fosterconnect.medication.Medication

@DrawableRes
fun CoatColor.defaultProfileDrawable(): Int =
    when (this) {
        CoatColor.BLACK -> R.drawable.black
        CoatColor.WHITE -> R.drawable.white
        CoatColor.GRAY_TABBY -> R.drawable.all_grey_tabby
        CoatColor.DILUTED -> R.drawable.dilutedtabby
        CoatColor.CALICO -> R.drawable.calico
        CoatColor.TORTOISESHELL -> R.drawable.tortoiseshell
        CoatColor.TUXEDO -> R.drawable.tuxedo
        CoatColor.CHOC_FLAMEPOINT -> R.drawable.chocolateflamepoint
        CoatColor.ORANGE_TABBY -> R.drawable.default_kitten_profile
        CoatColor.GRAY -> R.drawable.grey_kitten
        CoatColor.GRAY_TUXEDO-> R.drawable.grey_with_socks
        CoatColor.GRAY_TABBY_TUXEDO -> R.drawable.greytabby
        CoatColor.ORANGE_SPOTTED -> R.drawable.orange_white_spot
        CoatColor.GRAY_FLAMEPOINT -> R.drawable.grey_flamepoint
        CoatColor.ORANGE_FLAMEPOINT -> R.drawable.flamepoint
        CoatColor.BLACK_SPOTTED -> R.drawable.black_white_spotted
        CoatColor.TAB_TORTIE,
        CoatColor.BROWN_TABBY,
        CoatColor.BLOTCHED_TABBY,
            -> R.drawable.default_kitten_profile
    }

enum class Sex(val display: String) {
    MALE("Male"),
    FEMALE("Female"),
    NEUTERED("Neutered"),
    SPAYED("Spayed")
}

enum class Breed(val display: String) {
    DOMESTIC_SHORT_HAIR("Domestic Short Hair"),
    DOMESTIC_MEDIUM_HAIR("Domestic Medium Hair"),
    DOMESTIC_LONG_HAIR("Domestic Long Hair"),
}

enum class CoatColor(val display: String) {
    BLACK("Black"),
    WHITE("White"),
    GRAY("Gray"),
    GRAY_TABBY("Gray Tabby"),
    DILUTED("Diluted"),
    CALICO("Calico"),
    TORTOISESHELL("Tortoiseshell"),
    TAB_TORTIE("Tab Tortie"),
    ORANGE_TABBY("Orange Tabby"),
    BROWN_TABBY("Brown Tabby"),
    TUXEDO("Tuxedo"),
    GRAY_TUXEDO("Grey Tuxedo"),
    GRAY_TABBY_TUXEDO("Grey Tabby Tuxedo"),
    CHOC_FLAMEPOINT("Chocolate Flamepoint"),
    GRAY_FLAMEPOINT("Gray Flamepoint"),
    ORANGE_FLAMEPOINT("Flamepoint"),
    BLACK_SPOTTED("Black Spotted"),
    ORANGE_SPOTTED("Orange Spotted"),
    BLOTCHED_TABBY("Blotched Tabby")
}

data class FosterPhoto(
    val id: String,
    val uri: String,
    val addedAtMillis: Long
)

data class AdministeredTreatment(
    val id: Long = 0,
    val treatmentType: String,
    val scheduledDateMillis: Long,
    val administeredDateMillis: Long? = null,
    val doseGiven: String? = null
)

data class FosterCaseAnimal(
    val animalId: String,
    val fosterCaseId: String,
    val externalId: String = "",
    val name: String,
    val litterName: String? = null,
    val breed: Breed,
    val color: CoatColor,
    val sex: Sex,
    val intakeDateMillis: Long,
    val estimatedBirthdayMillis: Long? = null,
    val weightEntries: List<WeightEntry> = emptyList(),
    val stoolEntries: List<StoolEntry> = emptyList(),
    val eventEntries: List<EventEntry> = emptyList(),
    val medications: List<Medication> = emptyList(),
    val photos: List<FosterPhoto> = emptyList(),
    val nextVaccineDateMillis: Long? = null,
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
    val litterName: String? = null,
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
