package com.example.fosterconnect.data

import android.content.Context
import com.example.fosterconnect.data.db.AdministeredTreatmentEntity
import com.example.fosterconnect.data.db.AppDatabase
import com.example.fosterconnect.data.db.KittenEntity
import com.example.fosterconnect.data.db.KittenWithDetails
import com.example.fosterconnect.data.db.MedicationEntity
import com.example.fosterconnect.data.db.MessageEntity
import com.example.fosterconnect.data.db.WeightEntryEntity
import com.example.fosterconnect.foster.AdministeredTreatment
import com.example.fosterconnect.foster.Breed
import com.example.fosterconnect.foster.CoatColor
import com.example.fosterconnect.foster.Kitten
import com.example.fosterconnect.foster.Sex
import com.example.fosterconnect.history.Message
import com.example.fosterconnect.history.WeightEntry
import com.example.fosterconnect.medication.Medication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object KittenRepository {

    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _kittensFlow = MutableStateFlow<List<Kitten>>(emptyList())
    val kittensFlow: StateFlow<List<Kitten>> = _kittensFlow.asStateFlow()

    private val _messagesFlow = MutableStateFlow<List<Message>>(emptyList())
    val messagesFlow: StateFlow<List<Message>> = _messagesFlow.asStateFlow()

    val kittens: List<Kitten> get() = _kittensFlow.value
    val messages: List<Message> get() = _messagesFlow.value

    fun init(context: Context) {
        if (::db.isInitialized) return
        db = AppDatabase.getInstance(context)

        scope.launch {
            if (db.kittenDao().count() == 0) {
                seedDefaults()
            }
        }
        scope.launch {
            db.kittenDao().observeAllWithDetails().collect { list ->
                _kittensFlow.value = list.map { it.toDomain() }
            }
        }
        scope.launch {
            db.messageDao().observeAll().collect { list ->
                _messagesFlow.value = list.map { it.toDomain() }
            }
        }
    }

    fun getKitten(id: String): Kitten? = _kittensFlow.value.find { it.id == id }

    fun addMessage(message: Message) {
        scope.launch { db.messageDao().insert(message.toEntity()) }
    }

    fun markMessageRead(messageId: String) {
        scope.launch { db.messageDao().markRead(messageId) }
    }

    fun addWeight(kittenId: String, entry: WeightEntry) {
        scope.launch {
            db.kittenDao().insertWeightEntry(
                WeightEntryEntity(
                    kittenId = kittenId,
                    dateMillis = entry.dateMillis,
                    weightGrams = entry.weightGrams
                )
            )
        }
    }

    fun setWeightDeclineWarned(kittenId: String, warned: Boolean) {
        scope.launch { db.kittenDao().updateWeightDeclineWarned(kittenId, warned) }
    }

    fun addMedication(kittenId: String, medication: Medication) {
        scope.launch { db.kittenDao().insertMedication(medication.toEntity(kittenId)) }
    }

    fun stopMedication(kittenId: String, medicationId: String) {
        scope.launch {
            db.kittenDao().stopMedication(medicationId, System.currentTimeMillis())
        }
    }

    fun setBirthday(kittenId: String, birthdayMillis: Long) {
        scope.launch { db.kittenDao().updateBirthday(kittenId, birthdayMillis) }
    }

    fun setExternalId(kittenId: String, externalId: String) {
        scope.launch { db.kittenDao().updateExternalId(kittenId, externalId) }
    }

    fun markTreatmentAdministered(kittenId: String, treatment: AdministeredTreatment) {
        scope.launch {
            db.kittenDao().insertAdministeredTreatment(
                AdministeredTreatmentEntity(
                    kittenId = kittenId,
                    treatmentType = treatment.treatmentType,
                    scheduledDateMillis = treatment.scheduledDateMillis,
                    administeredDateMillis = treatment.administeredDateMillis
                )
            )
        }
    }

    fun markAdopted(kittenId: String) {
        scope.launch {
            db.kittenDao().markAdopted(kittenId, System.currentTimeMillis())
        }
    }

    private suspend fun seedDefaults() {
        val now = System.currentTimeMillis()
        val twoWeeksAgo = now - 14L * 24 * 60 * 60 * 1000
        val threeWeeksAgo = now - 21L * 24 * 60 * 60 * 1000
        db.kittenDao().insertKitten(
            KittenEntity(
                id = java.util.UUID.randomUUID().toString(),
                externalId = "F-2023-001",
                name = "Luna",
                breed = Breed.DOMESTIC_SHORT_HAIR.name,
                color = CoatColor.GRAY_TABBY.name,
                sex = Sex.FEMALE.name,
                isAltered = false,
                intakeDateMillis = now,
                estimatedBirthdayMillis = threeWeeksAgo,
                weightDeclineWarned = false,
                isAdopted = false,
                adoptionDateMillis = null
            )
        )
        db.kittenDao().insertKitten(
            KittenEntity(
                id = java.util.UUID.randomUUID().toString(),
                externalId = "F-2023-002",
                name = "Mochi",
                breed = Breed.SIAMESE.name,
                color = CoatColor.WHITE.name,
                sex = Sex.MALE.name,
                isAltered = false,
                intakeDateMillis = now,
                estimatedBirthdayMillis = twoWeeksAgo,
                weightDeclineWarned = false,
                isAdopted = false,
                adoptionDateMillis = null
            )
        )
    }

    private fun KittenWithDetails.toDomain(): Kitten = Kitten(
        id = kitten.id,
        externalId = kitten.externalId,
        name = kitten.name,
        breed = Breed.valueOf(kitten.breed),
        color = CoatColor.valueOf(kitten.color),
        sex = Sex.valueOf(kitten.sex),
        isAltered = kitten.isAltered,
        intakeDateMillis = kitten.intakeDateMillis,
        estimatedBirthdayMillis = kitten.estimatedBirthdayMillis,
        weightEntries = weightEntries
            .sortedBy { it.dateMillis }
            .map { WeightEntry(it.dateMillis, it.weightGrams) }
            .toMutableList(),
        medications = medications.map {
            Medication(
                id = it.id,
                name = it.name,
                strength = it.strength,
                instructions = it.instructions,
                startDateMillis = it.startDateMillis,
                endDateMillis = it.endDateMillis
            )
        }.toMutableList(),
        administeredTreatments = administeredTreatments.map {
            AdministeredTreatment(
                treatmentType = it.treatmentType,
                scheduledDateMillis = it.scheduledDateMillis,
                administeredDateMillis = it.administeredDateMillis
            )
        }.toMutableList(),
        weightDeclineWarned = kitten.weightDeclineWarned,
        isAdopted = kitten.isAdopted,
        adoptionDateMillis = kitten.adoptionDateMillis
    )

    private fun MessageEntity.toDomain(): Message = Message(
        id = id,
        title = title,
        content = content,
        timestamp = timestamp,
        kittenId = kittenId,
        isRead = isRead
    )

    private fun Message.toEntity(): MessageEntity = MessageEntity(
        id = id,
        title = title,
        content = content,
        timestamp = timestamp,
        kittenId = kittenId,
        isRead = isRead
    )

    private fun Medication.toEntity(kittenId: String): MedicationEntity = MedicationEntity(
        id = id,
        kittenId = kittenId,
        name = name,
        strength = strength,
        instructions = instructions,
        startDateMillis = startDateMillis,
        endDateMillis = endDateMillis
    )
}
