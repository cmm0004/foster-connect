package com.example.fosterconnect.medication

data class Medication(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val strength: String? = null,        // e.g. "0.3%", "250mg", "50mg/ml"
    val instructions: String = "",       // free-text directions from label
    val startDateMillis: Long,
    val endDateMillis: Long? = null
) {
    val isActive: Boolean get() = endDateMillis == null
}
