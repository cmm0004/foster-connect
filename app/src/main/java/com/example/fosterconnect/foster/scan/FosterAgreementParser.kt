package com.example.fosterconnect.foster.scan

import android.util.Log
import com.example.fosterconnect.foster.Breed
import com.example.fosterconnect.foster.CoatColor
import com.example.fosterconnect.foster.Sex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedVaccination(
    val name: String,
    val lastGivenMillis: Long? = null,
    val dueMillis: Long? = null
)

enum class AgeUnit { WEEKS, MONTHS, YEARS }

data class ParsedAge(val value: Int, val unit: AgeUnit) {
    fun display(): String = when (unit) {
        AgeUnit.WEEKS -> "$value weeks"
        AgeUnit.MONTHS -> "$value months"
        AgeUnit.YEARS -> "$value years"
    }
    fun estimatedBirthdayMillis(now: Long = System.currentTimeMillis()): Long {
        val millis = when (unit) {
            AgeUnit.WEEKS -> value * 7L * 24 * 60 * 60 * 1000
            AgeUnit.MONTHS -> value * 30L * 24 * 60 * 60 * 1000
            AgeUnit.YEARS -> value * 365L * 24 * 60 * 60 * 1000
        }
        return now - millis
    }
}

data class ParsedFosterAgreement(
    val animalExternalId: String? = null,
    val name: String? = null,
    val breed: Breed? = null,
    val breedRaw: String? = null,
    val color: CoatColor? = null,
    val colorRaw: String? = null,
    val sex: Sex? = null,
    val age: ParsedAge? = null,
    val intakeDateMillis: Long? = null,
    val lastWeightGrams: Float? = null,
    val lastWeightDateMillis: Long? = null,
    val vaccinations: List<ParsedVaccination> = emptyList(),
    val fosterParentName: String? = null,
    val fosterParentPhone: String? = null
) {
    fun summary(): String = buildString {
        appendLine("animalExternalId = $animalExternalId")
        appendLine("name             = $name")
        appendLine("breed            = $breed (raw='$breedRaw')")
        appendLine("color            = $color (raw='$colorRaw')")
        appendLine("sex              = $sex")
        appendLine("age              = ${age?.display()}")
        appendLine("intakeDate       = ${intakeDateMillis?.let { formatDate(it) }}")
        appendLine("lastWeightGrams  = $lastWeightGrams")
        appendLine("lastWeightDate   = ${lastWeightDateMillis?.let { formatDate(it) }}")
        appendLine("fosterParentName = $fosterParentName")
        appendLine("fosterParentPhone= $fosterParentPhone")
        appendLine("vaccinations     = ${vaccinations.size}")
        vaccinations.forEach { v ->
            appendLine("  - ${v.name}: last=${v.lastGivenMillis?.let(::formatDate)} due=${v.dueMillis?.let(::formatDate)}")
        }
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}

object FosterAgreementParser {

    private const val TAG = "FosterParser"
    var debug: Boolean = true
    private fun d(msg: String) { if (debug) Log.d(TAG, msg) }

    private val animalIdRegex = Regex("""\bA\d{6,}\b""")
    private val phoneRegex = Regex("""\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}""")
    private val fosterParentRegex = Regex("""^([A-Z][A-Za-z'\- ]+?)\s*/\s*P\d{5,}""")
    private val dayOfWeekDateRegex =
        Regex("""(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,?\s*(\d{1,2}/\d{1,2}/\d{2,4})""", RegexOption.IGNORE_CASE)
    private val shortDateRegex = Regex("""\b(\d{1,2}/\d{1,2}/\d{2,4})\b""")
    private val ageRegex = Regex("""^\s*(\d{1,2})\s*([MWY])\s*$""", RegexOption.IGNORE_CASE)
    private val alterStatusRegex = Regex("""^\s*([NSI])\s*$""")
    private val weightNumberRegex = Regex("""^\s*(\d{1,3}\.\d{1,2})\s*$""")

    private val knownVaccines = listOf("FVRCP", "Pyrantel", "Rabies", "Bordetella", "DAPP", "DHPP", "DA2PP")

    fun parse(rawText: String): ParsedFosterAgreement {

        val rawLines = rawText.lines()
        val lines = rawLines.map { it.trim().trimStart('|').trim() }.filter { it.isNotEmpty() }

        d("parse: rawLines=${rawLines.size}, cleanedLines=${lines.size}")
        lines.forEachIndexed { i, line -> d("line[%02d] |%s|".format(i, line)) }

        val animalId = lines.firstNotNullOfOrNull { line ->
            animalIdRegex.find(line)?.value
        }
        d("animalId -> $animalId")

        val name = lines.firstNotNullOfOrNull { line ->
            val match = animalIdRegex.find(line) ?: return@firstNotNullOfOrNull null
            val after = line.substring(match.range.last + 1).trim()
            after.takeIf { it.isNotEmpty() && !it.contains('/') }
        }
        d("name -> $name")

        val breedRaw = lines.firstOrNull { looksLikeBreedLine(it) }
        val breed = breedRaw?.let { matchBreed(it) }
        d("breedRaw -> '$breedRaw' -> $breed")

        val colorRaw = lines.firstOrNull { looksLikeColorLine(it) }
        val color = colorRaw?.let { matchColor(it) }
        d("colorRaw -> '$colorRaw' -> $color")

        val ageMatch = lines.firstNotNullOfOrNull { line -> ageRegex.matchEntire(line) }
        d("ageMatch -> ${ageMatch?.value}")
        val ageValue = ageMatch?.groupValues?.get(1)?.toIntOrNull()
        val age = ageValue?.let {
            when (ageMatch.groupValues[2].uppercase()) {
                "W" -> ParsedAge(it, AgeUnit.WEEKS)
                "M" -> ParsedAge(it, AgeUnit.MONTHS)
                "Y" -> ParsedAge(it, AgeUnit.YEARS)
                else -> null
            }
        }

        // Shelter convention: N = neutered, S = spayed, I = intact (sex unknown).
        val alterStatus = lines.firstNotNullOfOrNull { line -> alterStatusRegex.matchEntire(line)?.groupValues?.get(1)?.uppercase() }
        d("alterStatus -> $alterStatus")
        val sex = when (alterStatus) {
            "N" -> Sex.NEUTERED
            "S" -> Sex.SPAYED
            else -> null
        }

        val intakeDateMillis = lines.firstNotNullOfOrNull { line ->
            dayOfWeekDateRegex.find(line)?.groupValues?.get(1)?.let(::parseDate)
        }
        d("intakeDateMillis -> $intakeDateMillis")

        val (lastWeightGrams, lastWeightDateMillis) = parseWeight(lines)
        d("lastWeight -> $lastWeightGrams g @ $lastWeightDateMillis")

        val vaccinations = parseVaccinations(lines)
        d("vaccinations -> ${vaccinations.map { it.name }}")

        val fosterParentName = lines.firstNotNullOfOrNull { line ->
            fosterParentRegex.find(line)?.groupValues?.get(1)?.trim()
        }
        val fosterParentPhone = lines.firstNotNullOfOrNull { line ->
            phoneRegex.find(line)?.value?.takeIf { !line.contains("751-5772") && !line.contains("fax") }
        }

        return ParsedFosterAgreement(
            animalExternalId = animalId,
            name = name,
            breed = breed,
            breedRaw = breedRaw,
            color = color,
            colorRaw = colorRaw,
            sex = sex,
            age = age,
            intakeDateMillis = intakeDateMillis,
            lastWeightGrams = lastWeightGrams,
            lastWeightDateMillis = lastWeightDateMillis,
            vaccinations = vaccinations,
            fosterParentName = fosterParentName,
            fosterParentPhone = fosterParentPhone
        )
    }

    private fun looksLikeBreedLine(line: String): Boolean {
        val lower = line.lowercase()
        return Breed.values().any { breed ->
            val display = breed.display.lowercase()
            lower.startsWith(display.take(6)) && lower.length <= display.length + 4
        }
    }

    private fun matchBreed(raw: String): Breed? {
        val lower = raw.lowercase().trim()
        // Longest-display match wins (so "Domestic Short Hair" beats "Domestic")
        return Breed.values()
            .filter { breed ->
                val display = breed.display.lowercase()
                display.startsWith(lower) || lower.startsWith(display.take(lower.length.coerceAtMost(display.length)))
            }
            .maxByOrNull { it.display.length }
    }

    private fun looksLikeColorLine(line: String): Boolean {
        val colorWords = CoatColor.values().flatMap { it.display.lowercase().split(' ') }.toSet()
        val tokens = line.lowercase().split('/', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 4) return false
        // Every token must be a recognized whole color word (no single-letter false positives).
        return tokens.all { token -> token.length >= 3 && token in colorWords }
    }

    private fun matchColor(raw: String): CoatColor? {
        val firstToken = raw.split('/', ',').firstOrNull()?.trim().orEmpty()
        if (firstToken.isEmpty()) return null
        return CoatColor.values().firstOrNull { it.display.equals(firstToken, ignoreCase = true) }
            ?: CoatColor.values().firstOrNull { it.display.contains(firstToken, ignoreCase = true) }
    }

    private fun parseWeight(lines: List<String>): Pair<Float?, Long?> {
        // Weights on a foster agreement are in pounds; convert to grams.
        val weightLb = lines.firstNotNullOfOrNull { line ->
            weightNumberRegex.matchEntire(line)?.groupValues?.get(1)?.toFloatOrNull()
        } ?: return null to null
        val grams = weightLb * 453.592f
        // Nearest plausible date in the list; prefer one not matching the intake/day-of-week line.
        val date = lines.firstNotNullOfOrNull { line ->
            if (dayOfWeekDateRegex.containsMatchIn(line)) return@firstNotNullOfOrNull null
            shortDateRegex.find(line)?.groupValues?.get(1)?.let(::parseDate)
        }
        return grams to date
    }

    private fun parseVaccinations(lines: List<String>): List<ParsedVaccination> {
        val found = mutableListOf<ParsedVaccination>()
        for (vaccine in knownVaccines) {
            val hit = lines.any { it.equals(vaccine, ignoreCase = true) }
            if (hit) found.add(ParsedVaccination(name = vaccine))
        }
        // We can't reliably pair dates to vaccine names without column info; leave dates null for now.
        return found
    }

    private fun parseDate(raw: String): Long? {
        val parts = raw.split('/')
        if (parts.size != 3) return null
        val yearLen = parts[2].length
        val pattern = if (yearLen == 2) "M/d/yy" else "M/d/yyyy"
        return runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(raw)?.time
        }.getOrNull()
    }
}
