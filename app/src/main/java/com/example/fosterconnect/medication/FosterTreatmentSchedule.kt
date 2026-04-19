package com.example.fosterconnect.medication

import com.example.fosterconnect.foster.AdministeredTreatment

enum class StandardTreatment(val displayName: String) {
    FVRCP("FVRCP Vaccine"),
    PYRANTEL("Pyrantel"),
    PONAZURIL("Ponazuril")
}

data class ScheduledDose(
    val treatment: StandardTreatment,
    val scheduledDateMillis: Long,
    val doseLabel: String,
    val doseNumber: Int,
    val isPast: Boolean,
    val isAdministered: Boolean
)

object FosterTreatmentSchedule {

    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    private const val TWO_WEEKS_MS = 14L * ONE_DAY_MS
    private const val GRAMS_PER_LB = 453.592f

    // Ponazuril dosing table: (weight in lbs, dose in cc)
    private val ponazurilTable: List<Pair<Float, Float>> = listOf(
        0.25f to 0.05f,
        0.50f to 0.10f,
        0.75f to 0.20f,
        1.00f to 0.20f,
        1.25f to 0.25f,
        1.50f to 0.30f,
        1.75f to 0.35f,
        2.00f to 0.40f,
        2.25f to 0.40f,
        2.50f to 0.50f,
        2.75f to 0.50f,
        3.00f to 0.55f,
        3.25f to 0.60f,
        3.50f to 0.65f,
        3.75f to 0.70f,
        4.00f to 0.75f,
        4.25f to 0.80f,
        4.50f to 0.85f,
        4.75f to 0.90f,
        5.00f to 0.95f,
        5.25f to 1.00f,
        5.50f to 1.00f,
        5.75f to 1.10f,
        6.00f to 1.10f,
        6.25f to 1.20f,
        6.50f to 1.20f,
        6.75f to 1.30f,
        7.00f to 1.30f,
        7.25f to 1.40f,
        7.50f to 1.40f,
        7.75f to 1.50f,
        8.00f to 1.50f,
        8.25f to 1.50f,
        8.50f to 1.60f,
        8.75f to 1.60f,
        9.00f to 1.70f,
        9.25f to 1.70f,
        9.50f to 1.80f,
        9.75f to 1.80f,
        10.0f to 2.00f,
        15.0f to 3.00f,
        20.0f to 4.00f,
        30.0f to 6.00f,
        40.0f to 8.00f,
        50.0f to 10.0f,
        60.0f to 12.0f,
        70.0f to 14.0f,
        80.0f to 16.0f
    )

    fun generateSchedule(
        nextVaccineDateMillis: Long?,
        latestWeightGrams: Float?,
        latestWeightDateMillis: Long?,
        administeredTreatments: List<AdministeredTreatment>
    ): List<ScheduledDose> {
        if (nextVaccineDateMillis == null) return emptyList()

        val now = System.currentTimeMillis()
        val todayStart = now - (now % ONE_DAY_MS)
        val doses = mutableListOf<ScheduledDose>()

        var doseNumber = 1
        var doseDate = nextVaccineDateMillis
        val maxDoses = 10

        while (doseNumber <= maxDoses) {
            val isPast = doseDate < now
            for (treatment in StandardTreatment.entries) {
                val isAdministered = administeredTreatments.any {
                    it.treatmentType == treatment.name && it.scheduledDateMillis == doseDate
                }
                doses.add(
                    ScheduledDose(
                        treatment = treatment,
                        scheduledDateMillis = doseDate,
                        doseLabel = getDoseLabel(treatment, isPast, todayStart, latestWeightGrams, latestWeightDateMillis),
                        doseNumber = doseNumber,
                        isPast = isPast,
                        isAdministered = isAdministered
                    )
                )
            }

            doseDate += TWO_WEEKS_MS
            doseNumber++
        }

        return doses
    }

    private fun getDoseLabel(
        treatment: StandardTreatment,
        isDueOrPast: Boolean,
        todayStart: Long,
        weightGrams: Float?,
        weightDateMillis: Long?
    ): String {
        return when (treatment) {
            StandardTreatment.FVRCP -> "1"
            StandardTreatment.PYRANTEL, StandardTreatment.PONAZURIL -> {
                if (!isDueOrPast) return "determined day-of"
                if (weightGrams == null || weightGrams <= 0f) return "Update weight"
                val weightIsToday = weightDateMillis != null && weightDateMillis >= todayStart
                if (!weightIsToday) return "Update weight"
                when (treatment) {
                    StandardTreatment.PYRANTEL -> "%.2f ml".format(pyrantelDoseMl(weightGrams))
                    StandardTreatment.PONAZURIL -> {
                        val cc = ponazurilDoseCc(weightGrams) ?: return "Weight too low"
                        "%.2f cc".format(cc)
                    }
                    else -> ""
                }
            }
        }
    }

    private fun pyrantelDoseMl(weightGrams: Float): Float {
        val lbs = weightGrams / GRAMS_PER_LB
        return lbs * 0.1f
    }

    private fun ponazurilDoseCc(weightGrams: Float): Float? {
        val lbs = weightGrams / GRAMS_PER_LB
        return ponazurilTable.lastOrNull { it.first <= lbs }?.second
    }
}
