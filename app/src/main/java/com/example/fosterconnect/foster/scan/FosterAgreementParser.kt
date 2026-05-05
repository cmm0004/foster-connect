package com.example.fosterconnect.foster.scan

import android.util.Log
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
    private val genderRegex = Regex("""^\s*([MF])\s*$""")
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

    fun parseMultiple(rawText: String): List<ParsedFosterAgreement> {
        val rawLines = rawText.lines()
        val lines = rawLines.map { it.trim().trimStart('|').trim() }.filter { it.isNotEmpty() }

        d("parseMultiple: rawLines=${rawLines.size}, cleanedLines=${lines.size}")
        lines.forEachIndexed { i, line -> d("line[%02d] |%s|".format(i, line)) }

        val animalIds = mutableListOf<String>()
        for (line in lines) {
            for (m in animalIdRegex.findAll(line)) {
                if (m.value !in animalIds) animalIds.add(m.value)
            }
        }

        d("parseMultiple: found ${animalIds.size} animal IDs: $animalIds")

        if (animalIds.size <= 1) return listOf(parse(rawText))

        val n = animalIds.size

        // Names: on the same line as the ID, or the next name-like line after it
        val names = arrayOfNulls<String>(n)
        var pendingNameIdx = -1
        for (line in lines) {
            if (isHeaderLine(line)) continue
            val idMatch = animalIdRegex.find(line)
            if (idMatch != null) {
                val idx = animalIds.indexOf(idMatch.value)
                if (idx < 0) continue
                val afterId = line.substring(idMatch.range.last + 1).trim().trimStart('|').trim()
                if (afterId.isNotEmpty() && looksLikeName(afterId)) {
                    names[idx] = afterId
                    pendingNameIdx = -1
                } else {
                    pendingNameIdx = idx
                }
            } else if (pendingNameIdx >= 0 && names[pendingNameIdx] == null && looksLikeName(line)) {
                names[pendingNameIdx] = line
                pendingNameIdx = -1
            }
        }

        // Collect each column's values in document order, take first N
        val colorValues = lines.filter { looksLikeColorLineExpanded(it) }.take(n)
        val weightValues = lines.mapNotNull { weightNumberRegex.matchEntire(it)?.groupValues?.get(1)?.toFloatOrNull() }.take(n)
        val ageValues = lines.mapNotNull { ageRegex.matchEntire(it)?.let(::matchEntireToAge) }.take(n)
        val genderValues = lines.mapNotNull { line ->
            genderRegex.matchEntire(line)?.groupValues?.get(1)?.uppercase()?.let {
                when (it) { "M" -> Sex.MALE; "F" -> Sex.FEMALE; else -> null }
            }
        }.take(n)
        val weightDateValues = lines.filter { line ->
            !dayOfWeekDateRegex.containsMatchIn(line) &&
            shortDateRegex.matchEntire(line.trim()) != null
        }.mapNotNull { shortDateRegex.find(it)?.groupValues?.get(1)?.let(::parseDate) }.take(n)

        // Shared data from the full document
        val intakeDateMillis = lines.firstNotNullOfOrNull { line ->
            dayOfWeekDateRegex.find(line)?.groupValues?.get(1)?.let(::parseDate)
        }
        val fosterParentName = lines.firstNotNullOfOrNull { line ->
            fosterParentRegex.find(line)?.groupValues?.get(1)?.trim()
        }
        val fosterParentPhone = lines.firstNotNullOfOrNull { line ->
            phoneRegex.find(line)?.value?.takeIf { !line.contains("751-5772") && !line.contains("fax") }
        }
        val vaccinations = parseVaccinations(lines)

        d("parseMultiple columns: colors=$colorValues weights=$weightValues ages=$ageValues genders=$genderValues")

        return (0 until n).map { i ->
            val colorRaw = colorValues.getOrNull(i)
            ParsedFosterAgreement(
                animalExternalId = animalIds[i],
                name = names[i],
                color = colorRaw?.let { matchColor(it) },
                colorRaw = colorRaw,
                sex = genderValues.getOrNull(i),
                age = ageValues.getOrNull(i),
                intakeDateMillis = intakeDateMillis,
                lastWeightGrams = weightValues.getOrNull(i)?.let { it * 453.592f },
                lastWeightDateMillis = weightDateValues.getOrNull(i),
                fosterParentName = fosterParentName,
                fosterParentPhone = fosterParentPhone,
                vaccinations = vaccinations
            )
        }
    }

    private fun matchEntireToAge(match: MatchResult): ParsedAge? {
        val v = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2].uppercase()) {
            "W" -> ParsedAge(v, AgeUnit.WEEKS)
            "M" -> ParsedAge(v, AgeUnit.MONTHS)
            "Y" -> ParsedAge(v, AgeUnit.YEARS)
            else -> null
        }
    }

    private val colorAbbreviations = mapOf(
        "brn" to "Brown", "blk" to "Black", "wht" to "White",
        "org" to "Orange", "gry" to "Gray", "gey" to "Gray"
    )

    private fun isHeaderLine(line: String): Boolean {
        val lower = line.lowercase()
        return (lower.contains("animal") && lower.contains("id")) ||
            lower == "color" || lower == "breed" || lower == "gender" ||
            lower.contains("last weight") || lower.contains("weight date") ||
            lower == "age" || lower.contains("age gender")
    }

    private fun looksLikeName(text: String): Boolean {
        if (text.length < 2 || text.length > 40) return false
        if (!text.all { c -> c.isLetter() || c.isWhitespace() || c == '-' || c == '\'' }) return false
        if (looksLikeColorLine(text) || looksLikeColorLineExpanded(text)) return false
        val lower = text.lowercase()
        if (lower == "breed" || lower == "color" || lower == "gender" || lower == "age" ||
            lower.contains("domestic") || lower.contains("humane") || lower.contains("colorado") ||
            lower.contains("foster") || lower.contains("weight")) return false
        return true
    }

    private fun looksLikeColorLineExpanded(line: String): Boolean {
        val colorWords = CoatColor.values().flatMap { it.display.lowercase().split(' ') }.toSet()
        val tokens = line.lowercase().split('/', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 5) return false
        val expanded = tokens.map { colorAbbreviations[it]?.lowercase() ?: it }
        return expanded.all { token -> token.length >= 3 && token in colorWords }
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
