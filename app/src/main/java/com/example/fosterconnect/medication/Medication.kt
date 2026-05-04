package com.example.fosterconnect.medication

data class Medication(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val dose: String = "",
    val doseUnit: String = "",
    val route: String = "",
    val frequency: String = "",
    val instructions: String = "",
    val startDateMillis: Long,
    val endDateMillis: Long? = null
) {
    val isActive: Boolean get() = endDateMillis == null || endDateMillis > System.currentTimeMillis()
}
