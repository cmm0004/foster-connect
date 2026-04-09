package com.example.fosterconnect.medication.scan

/**
 * Parses raw OCR text from a Humane Colorado medication label.
 *
 * Expected label structure:
 *   A#: 1347542
 *   "Squiggleworm"
 *   Please administer 1-2 drops in each eye
 *   at least TWICE daily for 7 days.
 *   Amt:
 *   Date: ...
 *   Drug: Tobramycin Strength:0.3%
 *   Exp: ...
 */
object MedicationLabelParser {

    private val animalIdRegex = Regex("""A\s*#\s*:?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val drugRegex = Regex("""Drug\s*:\s*([A-Za-z][A-Za-z0-9\-]*)""", RegexOption.IGNORE_CASE)
    private val strengthRegex = Regex("""Strength\s*:\s*(\S+)""", RegexOption.IGNORE_CASE)
    private val quotedNameRegex = Regex(""""[^"]+"""")
    private val structuredPrefixes = listOf("a#", "amt", "date", "drug", "exp", "strength", "telephone", "veterinarian")

    fun parse(rawText: String): ParsedMedication {
        return ParsedMedication(
            animalId = extractAnimalId(rawText),
            name = extractName(rawText),
            strength = extractStrength(rawText),
            instructions = extractInstructions(rawText),
            rawText = rawText
        )
    }

    private fun extractAnimalId(text: String): String? {
        val match = animalIdRegex.find(text) ?: return null
        return "A${match.groupValues[1]}"
    }

    private fun extractName(text: String): String? {
        return drugRegex.find(text)?.groupValues?.get(1)
    }

    private fun extractStrength(text: String): String? {
        return strengthRegex.find(text)?.groupValues?.get(1)
    }

    /**
     * Instructions are the free-text lines between the quoted animal name and
     * the first structured prefix line ("Amt:", "Date:", "Drug:", etc).
     */
    private fun extractInstructions(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val quoteIndex = lines.indexOfFirst { quotedNameRegex.containsMatchIn(it) }
        if (quoteIndex == -1) return null

        val instructionLines = mutableListOf<String>()
        for (i in (quoteIndex + 1) until lines.size) {
            val line = lines[i]
            val lower = line.lowercase()
            if (structuredPrefixes.any { lower.startsWith(it) }) break
            instructionLines.add(line)
        }
        return if (instructionLines.isEmpty()) null else instructionLines.joinToString(" ")
    }
}
