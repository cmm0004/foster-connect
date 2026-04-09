package com.example.fosterconnect.foster

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

data class Kitten(
    val id: String = java.util.UUID.randomUUID().toString(),
    val externalId: String = "",
    val name: String,
    val breed: Breed,
    val color: CoatColor,
    val sex: Sex,
    val isAltered: Boolean,
    val intakeDateMillis: Long,
    val estimatedBirthdayMillis: Long? = null,
    val weightEntries: MutableList<WeightEntry> = mutableListOf(),
    val medications: MutableList<Medication> = mutableListOf(),
    val administeredTreatments: MutableList<AdministeredTreatment> = mutableListOf(),
    var weightDeclineWarned: Boolean = false,
    var isAdopted: Boolean = false,
    var adoptionDateMillis: Long? = null
) {
    val ageInWeeks: Int?
        get() {
            val bday = estimatedBirthdayMillis ?: return null
            val diffMillis = System.currentTimeMillis() - bday
            return (diffMillis / (7L * 24 * 60 * 60 * 1000)).toInt()
        }
}
