package com.example.fosterconnect.data

import android.content.Context
import com.example.fosterconnect.data.db.AnimalEntity
import com.example.fosterconnect.data.db.AnimalSpecies
import com.example.fosterconnect.data.db.AppDatabase
import com.example.fosterconnect.data.db.CaseMedicationEntity
import com.example.fosterconnect.data.db.CaseMessageEntity
import com.example.fosterconnect.data.db.CaseTreatmentEntity
import com.example.fosterconnect.data.db.CaseWeightEntity
import com.example.fosterconnect.data.db.CompletedFosterRecordEntity
import com.example.fosterconnect.data.db.CompletedFosterRecordWithAnimal
import com.example.fosterconnect.data.db.FosterCaseEntity
import com.example.fosterconnect.data.db.FosterCaseStatus
import com.example.fosterconnect.data.db.FosterCaseWithDetails
import com.example.fosterconnect.data.db.RankFacetEntityV2
import com.example.fosterconnect.data.db.RankingRecordEntity
import com.example.fosterconnect.foster.AdministeredTreatment
import com.example.fosterconnect.foster.Breed
import com.example.fosterconnect.foster.CoatColor
import com.example.fosterconnect.foster.CompletedFoster
import com.example.fosterconnect.foster.FosterCaseAnimal
import com.example.fosterconnect.foster.RankFacet
import com.example.fosterconnect.foster.Sex
import com.example.fosterconnect.history.Message
import com.example.fosterconnect.history.WeightEntry
import com.example.fosterconnect.medication.Medication
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray

object KittenRepository {

    private lateinit var db: AppDatabase
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _fosterCasesFlow = MutableStateFlow<List<FosterCaseAnimal>>(emptyList())
    val fosterCasesFlow: StateFlow<List<FosterCaseAnimal>> = _fosterCasesFlow.asStateFlow()

    private val _activeFostersFlow = MutableStateFlow<List<FosterCaseAnimal>>(emptyList())
    val activeFostersFlow: StateFlow<List<FosterCaseAnimal>> = _activeFostersFlow.asStateFlow()

    private val _completedFostersFlow = MutableStateFlow<List<CompletedFoster>>(emptyList())
    val completedFostersFlow: StateFlow<List<CompletedFoster>> = _completedFostersFlow.asStateFlow()

    private val _messagesFlow = MutableStateFlow<List<Message>>(emptyList())
    val messagesFlow: StateFlow<List<Message>> = _messagesFlow.asStateFlow()

    private val _facetsFlow = MutableStateFlow<List<RankFacet>>(emptyList())
    val facetsFlow: StateFlow<List<RankFacet>> = _facetsFlow.asStateFlow()

    private val _scoresFlow = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val scoresFlow: StateFlow<Map<String, Map<String, Int>>> = _scoresFlow.asStateFlow()

    private val _facetAveragesFlow = MutableStateFlow<Map<String, Double>>(emptyMap())
    val facetAveragesFlow: StateFlow<Map<String, Double>> = _facetAveragesFlow.asStateFlow()

    private val _rankingRecordsFlow = MutableStateFlow<List<RankingRecordEntity>>(emptyList())

    fun init(context: Context) {
        if (::db.isInitialized) return
        appContext = context.applicationContext
        db = AppDatabase.getInstance(appContext)

        scope.launch {
            if (db.animalDao().countAnimals() == 0) {

                seedHistoricalFosters()
            }
            if (db.rankingDao().rankFacetCount() == 0) {
                seedRankFacets()
            }
        }

        scope.launch {
            combine(
                db.animalDao().observeAnimals(),
                db.fosterCaseDao().observeAllCasesWithDetails()
            ) { animals, cases ->
                val animalById = animals.associateBy { it.id }
                cases.mapNotNull { details ->
                    val animal = animalById[details.fosterCase.animalId] ?: return@mapNotNull null
                    details.toDomain(animal)
                }
            }.collect { cases ->
                _fosterCasesFlow.value = cases
                _activeFostersFlow.value = cases.filter { !it.isCompleted }
            }
        }

        scope.launch {
            db.fosterCaseDao().observeCompletedRecords().collect { records ->
                _completedFostersFlow.value = records.map { it.toDomain() }
            }
        }

        scope.launch {
            db.fosterCaseDao().observeAllMessages().collect { messages ->
                _messagesFlow.value = messages.map { it.toDomain() }
            }
        }

        scope.launch {
            db.rankingDao().observeRankFacets().collect { facets ->
                _facetsFlow.value = facets.map { RankFacet(it.id, it.displayName, it.description) }
            }
        }

        scope.launch {
            db.rankingDao().observeAllRankingRecords().collect { records ->
                _rankingRecordsFlow.value = records.map { it.rankingRecord }
                _scoresFlow.value = records
                    .groupBy { it.rankingRecord.fosterCaseId ?: "__ignore__" }
                    .filterKeys { it != "__ignore__" }
                    .mapValues { (_, grouped) ->
                        grouped.maxByOrNull { it.rankingRecord.rankedAtMillis }
                            ?.scores
                            ?.associate { it.facetId to it.score }
                            .orEmpty()
                    }
            }
        }

        scope.launch {
            db.rankingDao().observeFacetAverages().collect { averages ->
                _facetAveragesFlow.value = averages.associate { it.facetId to it.averageScore }
            }
        }
    }

    fun getFosterCase(caseId: String): FosterCaseAnimal? =
        _fosterCasesFlow.value.find { it.fosterCaseId == caseId }

    fun getCompletedFoster(caseId: String): CompletedFoster? =
        _completedFostersFlow.value.find { it.fosterCaseId == caseId }

    fun getScoresForCase(caseId: String): Map<String, Int> =
        _scoresFlow.value[caseId].orEmpty()

    fun addMessage(message: Message) {
        scope.launch {
            db.fosterCaseDao().insertMessage(
                CaseMessageEntity(
                    id = message.id,
                    fosterCaseId = message.fosterCaseId,
                    title = message.title,
                    content = message.content,
                    timestamp = message.timestamp,
                    isRead = message.isRead
                )
            )
        }
    }

    fun markMessageRead(messageId: String) {
        scope.launch { db.fosterCaseDao().markMessageRead(messageId) }
    }

    fun addWeight(fosterCaseId: String, entry: WeightEntry) {
        scope.launch {
            db.fosterCaseDao().insertWeight(
                CaseWeightEntity(
                    fosterCaseId = fosterCaseId,
                    dateMillis = entry.dateMillis,
                    weightGrams = entry.weightGrams
                )
            )
        }
    }

    fun setWeightDeclineWarned(fosterCaseId: String, warned: Boolean) {
        scope.launch {
            db.fosterCaseDao().updateWeightDeclineWarned(
                caseId = fosterCaseId,
                warned = warned,
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun addMedication(fosterCaseId: String, medication: Medication) {
        scope.launch {
            db.fosterCaseDao().insertMedication(
                CaseMedicationEntity(
                    id = medication.id,
                    fosterCaseId = fosterCaseId,
                    name = medication.name,
                    strength = medication.strength,
                    instructions = medication.instructions,
                    startDateMillis = medication.startDateMillis,
                    endDateMillis = medication.endDateMillis,
                    isActive = medication.isActive
                )
            )
        }
    }

    fun stopMedication(medicationId: String) {
        scope.launch {
            db.fosterCaseDao().stopMedication(medicationId, System.currentTimeMillis())
        }
    }

    fun setBirthday(animalId: String, birthdayMillis: Long) {
        scope.launch {
            db.animalDao().updateBirthday(animalId, birthdayMillis, System.currentTimeMillis())
        }
    }

    fun setExternalId(animalId: String, externalId: String) {
        scope.launch {
            db.animalDao().updateExternalId(animalId, externalId, System.currentTimeMillis())
        }
    }

    fun markTreatmentAdministered(fosterCaseId: String, treatment: AdministeredTreatment) {
        scope.launch {
            db.fosterCaseDao().insertTreatment(
                CaseTreatmentEntity(
                    fosterCaseId = fosterCaseId,
                    treatmentType = treatment.treatmentType,
                    scheduledDateMillis = treatment.scheduledDateMillis,
                    administeredDateMillis = treatment.administeredDateMillis,
                    notes = null
                )
            )
        }
    }

    fun createFosterCase(
        externalId: String,
        name: String,
        breed: Breed,
        color: CoatColor,
        sex: Sex,
        isAlteredAtIntake: Boolean,
        intakeDateMillis: Long,
        estimatedBirthdayMillis: Long?,
        initialWeightGrams: Float?,
        initialWeightDateMillis: Long?
    ) {
        scope.launch {
            val now = System.currentTimeMillis()
            val animalId = UUID.randomUUID().toString()
            val caseId = UUID.randomUUID().toString()
            db.animalDao().insertAnimal(
                AnimalEntity(
                    id = animalId,
                    externalId = externalId.ifBlank { null },
                    name = name,
                    species = AnimalSpecies.CAT.name,
                    breed = breed.name,
                    color = color.name,
                    sex = sex.name,
                    estimatedBirthdayMillis = estimatedBirthdayMillis,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )
            db.fosterCaseDao().insertCase(
                FosterCaseEntity(
                    id = caseId,
                    animalId = animalId,
                    status = FosterCaseStatus.ACTIVE.name,
                    intakeDateMillis = intakeDateMillis,
                    outDateMillis = null,
                    isAlteredAtIntake = isAlteredAtIntake,
                    weightDeclineWarned = false,
                    notes = null,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )
            if (initialWeightGrams != null) {
                db.fosterCaseDao().insertWeight(
                    CaseWeightEntity(
                        fosterCaseId = caseId,
                        dateMillis = initialWeightDateMillis ?: intakeDateMillis,
                        weightGrams = initialWeightGrams
                    )
                )
            }
        }
    }

    fun markCaseCompleted(fosterCaseId: String) {
        scope.launch {
            val details = db.fosterCaseDao().getCaseWithDetails(fosterCaseId) ?: return@launch
            val animal = db.animalDao().getAnimal(details.fosterCase.animalId) ?: return@launch
            val completedAt = System.currentTimeMillis()
            val finalWeight = details.weights.maxByOrNull { it.dateMillis }?.weightGrams
            val firstWeight = details.weights.minByOrNull { it.dateMillis }?.weightGrams
            val medicalSummary = details.medications
                .sortedBy { it.startDateMillis }
                .joinToString(", ") { it.name }
                .takeIf { it.isNotBlank() }
            val treatmentSummary = details.treatments
                .groupBy { it.treatmentType }
                .entries
                .sortedBy { it.key }
                .joinToString(", ") { (type, entries) -> "$type x${entries.size}" }
                .takeIf { it.isNotBlank() }
            db.fosterCaseDao().closeCase(
                caseId = fosterCaseId,
                status = FosterCaseStatus.COMPLETED.name,
                outDateMillis = completedAt,
                updatedAtMillis = completedAt
            )
            db.fosterCaseDao().insertCompletedRecord(
                CompletedFosterRecordEntity(
                    id = "completed-$fosterCaseId",
                    animalId = animal.id,
                    fosterCaseId = fosterCaseId,
                    intakeDateMillis = details.fosterCase.intakeDateMillis,
                    outDateMillis = completedAt,
                    daysFostered = ((completedAt - details.fosterCase.intakeDateMillis) / MILLIS_PER_DAY).toInt()
                        .coerceAtLeast(0),
                    finalWeightGrams = finalWeight,
                    weightChangeGrams = if (firstWeight != null && finalWeight != null) {
                        finalWeight - firstWeight
                    } else {
                        null
                    },
                    medicalSummary = medicalSummary,
                    behaviorSummary = null,
                    placementSummary = treatmentSummary,
                    createdAtMillis = completedAt
                )
            )
        }
    }

    fun saveRankScores(animalId: String, fosterCaseId: String?, scores: Map<String, Int>) {
        scope.launch {
            val now = System.currentTimeMillis()
            val nextVersion = _rankingRecordsFlow.value
                .filter { it.animalId == animalId && it.fosterCaseId == fosterCaseId }
                .maxOfOrNull { it.rankVersion }
                ?.plus(1)
                ?: 1
            val rankingRecordId = UUID.randomUUID().toString()
            db.rankingDao().insertRankingRecord(
                RankingRecordEntity(
                    id = rankingRecordId,
                    animalId = animalId,
                    fosterCaseId = fosterCaseId,
                    rankedAtMillis = now,
                    rankVersion = nextVersion,
                    notes = null
                )
            )
            db.rankingDao().insertRankingScores(
                scores.map { (facetId, score) ->
                    com.example.fosterconnect.data.db.RankingScoreEntity(
                        rankingRecordId = rankingRecordId,
                        facetId = facetId,
                        score = score
                    )
                }
            )
        }
    }

    private suspend fun seedRankFacets() {
        val defaults = listOf(
            RankFacetEntityV2("prey_drive", "Prey Drive", "How driven they are to chase and hunt", 0),
            RankFacetEntityV2("cleanliness", "Cleanliness", "How tidy they keep themselves", 1),
            RankFacetEntityV2("noisiness", "Noisiness", "How vocal they are", 2),
            RankFacetEntityV2("cuddliness", "Cuddliness", "How much they enjoy being held", 3),
            RankFacetEntityV2("playfulness", "Playfulness", "How active and playful they are", 4)
        )
        defaults.forEach { db.rankingDao().insertRankFacet(it) }
    }


    private suspend fun seedHistoricalFosters() {
        val json = appContext.assets.open("historicalfosters.json").bufferedReader().use { it.readText() }
        val records = JSONArray(json)
        val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)

        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val externalId = record.optString("anumber").trim()
            val name = record.optString("name").trim()
            val outDate = record.optString("out_date").trim()
            if (externalId.isEmpty() || name.isEmpty() || outDate.isEmpty()) continue

            val completedAt = runCatching { dateFormat.parse(outDate)?.time }.getOrNull() ?: continue
            val animalId = "animal-$externalId"
            val caseId = "case-$externalId"
            db.animalDao().insertAnimal(
                AnimalEntity(
                    id = animalId,
                    externalId = externalId,
                    name = name,
                    species = AnimalSpecies.CAT.name,
                    breed = Breed.DOMESTIC_SHORT_HAIR.name,
                    color = CoatColor.BLACK.name,
                    sex = Sex.FEMALE.name,
                    estimatedBirthdayMillis = null,
                    createdAtMillis = completedAt,
                    updatedAtMillis = completedAt
                )
            )
            db.fosterCaseDao().insertCase(
                FosterCaseEntity(
                    id = caseId,
                    animalId = animalId,
                    status = FosterCaseStatus.COMPLETED.name,
                    intakeDateMillis = completedAt - 14L * MILLIS_PER_DAY,
                    outDateMillis = completedAt,
                    isAlteredAtIntake = true,
                    weightDeclineWarned = false,
                    notes = null,
                    createdAtMillis = completedAt - 14L * MILLIS_PER_DAY,
                    updatedAtMillis = completedAt
                )
            )
            db.fosterCaseDao().insertCompletedRecord(
                CompletedFosterRecordEntity(
                    id = "completed-$caseId",
                    animalId = animalId,
                    fosterCaseId = caseId,
                    intakeDateMillis = completedAt - 14L * MILLIS_PER_DAY,
                    outDateMillis = completedAt,
                    daysFostered = 14,
                    finalWeightGrams = null,
                    weightChangeGrams = null,
                    medicalSummary = null,
                    behaviorSummary = null,
                    placementSummary = null,
                    createdAtMillis = completedAt
                )
            )
        }
    }

    private fun FosterCaseWithDetails.toDomain(animal: AnimalEntity): FosterCaseAnimal = FosterCaseAnimal(
        animalId = animal.id,
        fosterCaseId = fosterCase.id,
        externalId = animal.externalId.orEmpty(),
        name = animal.name,
        breed = safeBreed(animal.breed),
        color = safeColor(animal.color),
        sex = safeSex(animal.sex),
        isAlteredAtIntake = fosterCase.isAlteredAtIntake,
        intakeDateMillis = fosterCase.intakeDateMillis,
        estimatedBirthdayMillis = animal.estimatedBirthdayMillis,
        weightEntries = weights.sortedBy { it.dateMillis }.map { WeightEntry(it.dateMillis, it.weightGrams) },
        medications = medications.sortedByDescending { it.startDateMillis }.map {
            Medication(
                id = it.id,
                name = it.name,
                strength = it.strength,
                instructions = it.instructions,
                startDateMillis = it.startDateMillis,
                endDateMillis = it.endDateMillis
            )
        },
        administeredTreatments = treatments.map {
            AdministeredTreatment(
                treatmentType = it.treatmentType,
                scheduledDateMillis = it.scheduledDateMillis,
                administeredDateMillis = it.administeredDateMillis
            )
        },
        messages = messages.map { it.toDomain() },
        weightDeclineWarned = fosterCase.weightDeclineWarned,
        outDateMillis = fosterCase.outDateMillis,
        isCompleted = fosterCase.status == FosterCaseStatus.COMPLETED.name
    )

    private fun CompletedFosterRecordWithAnimal.toDomain(): CompletedFoster = CompletedFoster(
        completedRecordId = completedRecord.id,
        animalId = animal.id,
        fosterCaseId = completedRecord.fosterCaseId,
        externalId = animal.externalId.orEmpty(),
        name = animal.name,
        breed = safeBreed(animal.breed),
        color = safeColor(animal.color),
        sex = safeSex(animal.sex),
        estimatedBirthdayMillis = animal.estimatedBirthdayMillis,
        intakeDateMillis = completedRecord.intakeDateMillis,
        outDateMillis = completedRecord.outDateMillis,
        daysFostered = completedRecord.daysFostered,
        finalWeightGrams = completedRecord.finalWeightGrams,
        weightChangeGrams = completedRecord.weightChangeGrams,
        medicalSummary = completedRecord.medicalSummary,
        behaviorSummary = completedRecord.behaviorSummary,
        placementSummary = completedRecord.placementSummary
    )

    private fun CaseMessageEntity.toDomain(): Message = Message(
        id = id,
        fosterCaseId = fosterCaseId,
        title = title,
        content = content,
        timestamp = timestamp,
        isRead = isRead
    )

    private fun safeBreed(raw: String): Breed = runCatching { Breed.valueOf(raw) }.getOrDefault(Breed.DOMESTIC_SHORT_HAIR)
    private fun safeColor(raw: String): CoatColor = runCatching { CoatColor.valueOf(raw) }.getOrDefault(CoatColor.BLACK)
    private fun safeSex(raw: String): Sex = runCatching { Sex.valueOf(raw) }.getOrDefault(Sex.FEMALE)

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}
