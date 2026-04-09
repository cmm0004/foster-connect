package com.example.fosterconnect.medication.scan

/**
 * Structured result of parsing a medication label image.
 * All extracted fields are nullable since the parser may only find some of them.
 */
data class ParsedMedication(
    val animalId: String? = null,        // e.g. "A1347542" — Humane Colorado A#
    val name: String? = null,            // e.g. "Tobramycin"
    val strength: String? = null,        // e.g. "0.3%"
    val instructions: String? = null,    // free-text directions
    val rawText: String
)
