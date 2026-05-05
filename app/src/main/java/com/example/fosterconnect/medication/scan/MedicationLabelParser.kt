package com.example.fosterconnect.medication.scan

import android.util.Log

/**
 * Parses raw OCR text from a Humane Colorado medication label.
 *
 * Labels have varying OCR line order — sometimes the instructions come before
 * the A# line, sometimes after the quoted kitten name. The parser therefore
 * doesn't rely on a positional anchor; it filters out everything that looks
 * like metadata or footer junk and treats the remainder as instructions.
 */
object MedicationLabelParser {

    private const val TAG = "MedLabelParser"

    private val animalIdRegex = Regex("""A\s*#\s*:?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val drugLineRegex = Regex("""Drug\s*:\s*([A-Za-z][A-Za-z0-9\- ]*)""", RegexOption.IGNORE_CASE)
    private val quotedNameRegex = Regex(""""[^"]+"""")
    private val bareDateRegex = Regex("""^\d{1,2}/\d{2,4}(/\d{2,4})?$""")
    private val structuredPrefixes = listOf("a#", "amt", "date", "drug", "exp", "strength", "telephone", "veterinarian")

    fun parse(rawText: String): ParsedMedication {
        val animalId = extractAnimalId(rawText)
        val name = extractName(rawText)
        val instructions = extractInstructions(rawText)
        Log.d(TAG, "parsed animalId=$animalId name=$name instructions=$instructions")
        return ParsedMedication(
            animalId = animalId,
            name = name,
            instructions = instructions,
            rawText = rawText
        )
    }

    private fun extractAnimalId(text: String): String? {
        val match = animalIdRegex.find(text) ?: return null
        return "A${match.groupValues[1]}"
    }

    private fun extractName(text: String): String? {
        for (line in text.lines()) {
            val m = drugLineRegex.find(line) ?: continue
            return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * Instructions live between the `Date:` line and the `Drug:` line. OCR
     * reading order sometimes puts the A# and the quoted kitten name before
     * the instructions and sometimes after, so we just take the whole slice
     * and drop any metadata lines inside it.
     */
    private fun extractInstructions(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        Log.d(TAG, "instructions: ${lines.size} non-empty lines")
        lines.forEachIndexed { i, line -> Log.d(TAG, "  line[$i]=\"$line\"") }

        val dateIndex = lines.indexOfFirst { it.lowercase().startsWith("date") }
        val drugIndex = lines.indexOfFirst { it.lowercase().startsWith("drug") }
        Log.d(TAG, "instructions: dateIndex=$dateIndex drugIndex=$drugIndex")
        if (dateIndex == -1 || drugIndex == -1 || drugIndex <= dateIndex + 1) {
            Log.d(TAG, "instructions: no valid Date:→Drug: slice")
            return null
        }

        val kept = mutableListOf<String>()
        for (i in (dateIndex + 1) until drugIndex) {
            val line = lines[i]
            val lower = line.lowercase()
            val hitPrefix = structuredPrefixes.firstOrNull { lower.startsWith(it) }
            if (hitPrefix != null) {
                Log.d(TAG, "instructions: skip line[$i] (prefix=$hitPrefix)")
                continue
            }
            if (quotedNameRegex.containsMatchIn(line)) {
                Log.d(TAG, "instructions: skip line[$i] (quoted-name)")
                continue
            }
            if (bareDateRegex.matches(line)) {
                Log.d(TAG, "instructions: skip line[$i] (bare-date)")
                continue
            }
            kept.add(line)
        }
        Log.d(TAG, "instructions: kept ${kept.size} line(s)")
        return if (kept.isEmpty()) null else kept.joinToString(" ")
    }
}
