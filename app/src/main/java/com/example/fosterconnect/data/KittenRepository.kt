package com.example.fosterconnect.data

import android.content.Context
import com.example.fosterconnect.data.db.AnimalEntity
import com.example.fosterconnect.data.db.AnimalSpecies
import com.example.fosterconnect.data.db.AppDatabase
import android.content.ContentResolver
import android.net.Uri
import com.example.fosterconnect.data.db.CaseMedicationEntity
import com.example.fosterconnect.data.db.CasePhotoEntity
import com.example.fosterconnect.data.db.CaseMessageEntity
import com.example.fosterconnect.data.db.CaseTreatmentEntity
import com.example.fosterconnect.data.db.CaseEventEntity
import com.example.fosterconnect.data.db.CaseStoolEntity
import com.example.fosterconnect.data.db.CaseWeightEntity
import com.example.fosterconnect.data.db.CompletedFosterRecordEntity
import com.example.fosterconnect.data.db.CompletedFosterRecordWithAnimal
import com.example.fosterconnect.data.db.FosterCaseEntity
import com.example.fosterconnect.data.db.FosterCaseStatus
import com.example.fosterconnect.data.db.FosterCaseWithDetails
import com.example.fosterconnect.data.db.AssignedTraitEntity
import com.example.fosterconnect.foster.AdministeredTreatment
import com.example.fosterconnect.foster.Breed
import com.example.fosterconnect.foster.CoatColor
import com.example.fosterconnect.foster.CollarColor
import com.example.fosterconnect.foster.CompletedFoster
import com.example.fosterconnect.foster.FosterCaseAnimal
import com.example.fosterconnect.foster.FosterPhoto
import com.example.fosterconnect.foster.Sex
import com.example.fosterconnect.foster.TraitCatalog
import com.example.fosterconnect.foster.TraitDefinition
import com.example.fosterconnect.history.Message
import com.example.fosterconnect.history.EventEntry
import com.example.fosterconnect.history.EventType
import com.example.fosterconnect.history.StoolEntry
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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

    private val _caseTraitScoresFlow = MutableStateFlow<Map<String, Int>>(emptyMap())
    val caseTraitScoresFlow: StateFlow<Map<String, Int>> = _caseTraitScoresFlow.asStateFlow()

    private lateinit var _traitCatalog: TraitCatalog

    fun init(context: Context) {
        if (::db.isInitialized) return
        appContext = context.applicationContext
        db = AppDatabase.getInstance(appContext)

        _traitCatalog = parseTraitCatalog(appContext)

        scope.launch {
            if (db.animalDao().countAnimals() == 0) {
                seedHistoricalFosters()
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
            db.traitDao().observeAllCaseScores().collect { scores ->
                _caseTraitScoresFlow.value = scores.associate { it.fosterCaseId to it.totalScore }
            }
        }
    }

    fun getTraitCatalog(): TraitCatalog = _traitCatalog

    fun observeTraitsForCase(animalId: String, fosterCaseId: String): Flow<List<AssignedTraitEntity>> =
        db.traitDao().observeTraitsForCase(animalId, fosterCaseId)

    fun addTrait(animalId: String, fosterCaseId: String, trait: TraitDefinition) {
        scope.launch {
            val existing = db.traitDao().getTraitsForCase(fosterCaseId)
            val conflicts = _traitCatalog.conflictMap[trait.trait].orEmpty()
            val conflicting = existing.filter { it.traitName in conflicts }.map { it.traitName }
            if (conflicting.isNotEmpty()) {
                db.traitDao().removeTraits(animalId, fosterCaseId, conflicting)
            }
            db.traitDao().insertTrait(
                AssignedTraitEntity(
                    animalId = animalId,
                    fosterCaseId = fosterCaseId,
                    traitName = trait.trait,
                    category = trait.category,
                    valence = trait.valence,
                    score = trait.score,
                    assignedAtMillis = System.currentTimeMillis(),
                    syncId = UUID.randomUUID().toString()
                )
            )
        }
    }

    fun removeTrait(animalId: String, fosterCaseId: String, traitName: String) {
        scope.launch { db.traitDao().removeTrait(animalId, fosterCaseId, traitName) }
    }

    fun getFosterCase(caseId: String): FosterCaseAnimal? =
        _fosterCasesFlow.value.find { it.fosterCaseId == caseId }

    fun getCompletedFoster(caseId: String): CompletedFoster? =
        _completedFostersFlow.value.find { it.fosterCaseId == caseId }

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
                    weightGrams = entry.weightGrams,
                    syncId = UUID.randomUUID().toString()
                )
            )
        }
    }

    fun addStool(fosterCaseId: String, entry: StoolEntry) {
        scope.launch {
            db.fosterCaseDao().insertStool(
                CaseStoolEntity(
                    fosterCaseId = fosterCaseId,
                    dateMillis = entry.dateMillis,
                    level = entry.level,
                    syncId = UUID.randomUUID().toString()
                )
            )
        }
    }

    fun addEvent(fosterCaseId: String, entry: EventEntry) {
        scope.launch {
            db.fosterCaseDao().insertEvent(
                CaseEventEntity(
                    fosterCaseId = fosterCaseId,
                    dateMillis = entry.dateMillis,
                    type = entry.type.name,
                    syncId = UUID.randomUUID().toString()
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
                    instructions = medication.instructions,
                    startDateMillis = medication.startDateMillis,
                    endDateMillis = medication.endDateMillis,
                    isActive = medication.isActive,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun addPhoto(fosterCaseId: String, uri: String): Boolean {
        val dao = db.fosterCaseDao()
        if (dao.photoCountFor(fosterCaseId) >= MAX_PHOTOS_PER_CASE) return false
        dao.insertPhoto(
            CasePhotoEntity(
                id = UUID.randomUUID().toString(),
                fosterCaseId = fosterCaseId,
                uri = uri,
                addedAtMillis = System.currentTimeMillis()
            )
        )
        return true
    }

    suspend fun deletePhoto(photoId: String) {
        db.fosterCaseDao().deletePhoto(photoId)
    }

    suspend fun prunePhotos(fosterCaseId: String, contentResolver: ContentResolver) {
        val photos = db.fosterCaseDao().photosFor(fosterCaseId)
        for (photo in photos) {
            val alive = runCatching {
                contentResolver.openInputStream(Uri.parse(photo.uri))?.use { true } ?: false
            }.getOrElse { false }
            if (!alive) {
                db.fosterCaseDao().deletePhoto(photo.id)
            }
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

    fun setLitterName(animalId: String, litterName: String?) {
        scope.launch {
            db.animalDao().updateLitterName(animalId, litterName, System.currentTimeMillis())
        }
    }

    fun setName(animalId: String, name: String) {
        scope.launch {
            db.animalDao().updateName(animalId, name, System.currentTimeMillis())
        }
    }

    fun setCollarColor(fosterCaseId: String, collarColor: CollarColor?) {
        scope.launch {
            db.fosterCaseDao().updateCollarColor(
                fosterCaseId,
                collarColor?.name,
                System.currentTimeMillis()
            )
        }
    }

    fun completeTreatmentDose(fosterCaseId: String) {
        scope.launch {
            db.fosterCaseDao().clearNextVaccineDate(fosterCaseId, System.currentTimeMillis())
        }
    }

    suspend fun scheduleNextTreatment(fosterCaseId: String, dateMillis: Long, includePonazuril: Boolean): Boolean {
        val existing = db.fosterCaseDao().countAdministeredTreatmentsOnDate(fosterCaseId, dateMillis)
        if (existing > 0) return false
        db.fosterCaseDao().deleteScheduledTreatments(fosterCaseId, dateMillis)
        db.fosterCaseDao().updateNextVaccineDate(fosterCaseId, dateMillis, System.currentTimeMillis())
        val treatments = mutableListOf("FVRCP", "PYRANTEL")
        if (includePonazuril) treatments.add("PONAZURIL")
        for (type in treatments) {
            db.fosterCaseDao().insertTreatment(
                CaseTreatmentEntity(
                    fosterCaseId = fosterCaseId,
                    treatmentType = type,
                    scheduledDateMillis = dateMillis,
                    administeredDateMillis = null,
                    notes = null,
                    syncId = UUID.randomUUID().toString()
                )
            )
        }
        return true
    }

    fun markTreatmentAdministered(treatmentId: Long, doseGiven: String?) {
        scope.launch {
            db.fosterCaseDao().markTreatmentAdministered(treatmentId, System.currentTimeMillis(), doseGiven)
        }
    }

    fun createFosterCase(
        externalId: String,
        name: String,
        litterName: String? = null,
        breed: Breed,
        color: CoatColor,
        sex: Sex,
        intakeDateMillis: Long,
        estimatedBirthdayMillis: Long?,
        initialWeightGrams: Float?,
        initialWeightDateMillis: Long?,
        nextVaccineDateMillis: Long? = null
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
                    litterName = litterName,
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
                    weightDeclineWarned = false,
                    nextVaccineDateMillis = nextVaccineDateMillis,
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
                        weightGrams = initialWeightGrams,
                        syncId = UUID.randomUUID().toString()
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
            db.fosterCaseDao().stopAllActiveMedications(fosterCaseId, completedAt)
            db.fosterCaseDao().deleteAllUnadministeredTreatments(fosterCaseId)
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

    fun reopenCase(fosterCaseId: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            db.fosterCaseDao().reopenCase(
                caseId = fosterCaseId,
                status = FosterCaseStatus.ACTIVE.name,
                updatedAtMillis = now
            )
            db.fosterCaseDao().deleteCompletedRecord(fosterCaseId)
        }
    }

    private fun parseTraitCatalog(context: Context): TraitCatalog {
        val json = context.assets.open("traits.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val categories = listOf("physical", "behavioral", "quirks", "vibe")
        val allTraits = mutableListOf<TraitDefinition>()
        for (cat in categories) {
            val arr = root.optJSONArray(cat) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                allTraits.add(
                    TraitDefinition(
                        trait = obj.getString("trait"),
                        category = cat,
                        valence = obj.getString("valence"),
                        score = obj.getInt("score")
                    )
                )
            }
        }
        val conflictsArr = root.getJSONArray("conflicts")
        val conflictMap = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until conflictsArr.length()) {
            val obj = conflictsArr.getJSONObject(i)
            val pair = obj.getJSONArray("pair")
            val a = pair.getString(0)
            val b = pair.getString(1)
            conflictMap.getOrPut(a) { mutableListOf() }.add(b)
            conflictMap.getOrPut(b) { mutableListOf() }.add(a)
        }
        return TraitCatalog(
            traits = allTraits,
            traitsByCategory = allTraits.groupBy { it.category },
            conflictMap = conflictMap
        )
    }

    private suspend fun seedHistoricalFosters() {
        val json = appContext.assets.open("historicalfosters.json").bufferedReader().use { it.readText() }
        val records = JSONArray(json)
        val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)

        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val externalId = record.optString("anumber").trim()
            val name = record.optString("name").trim()
            val litterName = record.optString("litter_name").trim().takeIf { it.isNotEmpty() }
            val outDate = record.optString("out_date").trim()
            if (externalId.isEmpty() || name.isEmpty() || outDate.isEmpty()) continue

            val completedAt = runCatching { dateFormat.parse(outDate)?.time }.getOrNull() ?: continue
            val sex = record.optString("sex").trim().takeIf { it.isNotEmpty() } ?: Sex.SPAYED.name
            val color = record.optString("color").trim().takeIf { it.isNotEmpty() } ?: CoatColor.BLACK.name
            val animalId = "animal-$externalId"
            val caseId = "case-$externalId"
            db.animalDao().insertAnimal(
                AnimalEntity(
                    id = animalId,
                    externalId = externalId,
                    name = name,
                    species = AnimalSpecies.CAT.name,
                    breed = Breed.DOMESTIC_SHORT_HAIR.name,
                    color = color,
                    sex = sex,
                    litterName = litterName,
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
        litterName = animal.litterName,
        breed = safeBreed(animal.breed),
        color = safeColor(animal.color),
        sex = safeSex(animal.sex),
        intakeDateMillis = fosterCase.intakeDateMillis,
        estimatedBirthdayMillis = animal.estimatedBirthdayMillis,
        nextVaccineDateMillis = fosterCase.nextVaccineDateMillis,
        weightEntries = weights.sortedBy { it.dateMillis }.map { WeightEntry(it.dateMillis, it.weightGrams) },
        stoolEntries = stools.sortedBy { it.dateMillis }.map { StoolEntry(it.dateMillis, it.level) },
        eventEntries = events.sortedBy { it.dateMillis }.mapNotNull { e ->
            val type = runCatching { EventType.valueOf(e.type) }.getOrNull() ?: return@mapNotNull null
            EventEntry(e.dateMillis, type)
        },
        medications = medications.sortedByDescending { it.startDateMillis }.map {
            Medication(
                id = it.id,
                name = it.name,
                instructions = it.instructions,
                startDateMillis = it.startDateMillis,
                endDateMillis = it.endDateMillis
            )
        },
        photos = photos.sortedBy { it.addedAtMillis }.map {
            FosterPhoto(id = it.id, uri = it.uri, addedAtMillis = it.addedAtMillis)
        },
        administeredTreatments = treatments.map {
            AdministeredTreatment(
                id = it.id,
                treatmentType = it.treatmentType,
                scheduledDateMillis = it.scheduledDateMillis,
                administeredDateMillis = it.administeredDateMillis,
                doseGiven = it.doseGiven
            )
        },
        messages = messages.map { it.toDomain() },
        collarColor = CollarColor.fromName(fosterCase.collarColor),
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
        litterName = animal.litterName,
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
    const val MAX_PHOTOS_PER_CASE = 10
}
