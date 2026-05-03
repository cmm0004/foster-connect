package com.example.fosterconnect.sync

import com.example.fosterconnect.data.db.*
import org.json.JSONArray
import org.json.JSONObject

data class SyncPayload(
    val deviceId: String,
    val timestampMillis: Long,
    val animals: List<AnimalEntity>,
    val fosterCases: List<FosterCaseEntity>,
    val weights: List<CaseWeightEntity>,
    val stools: List<CaseStoolEntity>,
    val events: List<CaseEventEntity>,
    val treatments: List<CaseTreatmentEntity>,
    val medications: List<CaseMedicationEntity>,
    val messages: List<CaseMessageEntity>,
    val completedRecords: List<CompletedFosterRecordEntity>,
    val traits: List<AssignedTraitEntity>
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("deviceId", deviceId)
        root.put("timestampMillis", timestampMillis)
        root.put("animals", JSONArray().apply {
            for (a in animals) put(JSONObject().apply {
                put("id", a.id)
                put("externalId", a.externalId ?: "")
                put("name", a.name)
                put("species", a.species)
                put("breed", a.breed)
                put("color", a.color)
                put("sex", a.sex)
                put("litterName", a.litterName ?: "")
                put("estimatedBirthdayMillis", a.estimatedBirthdayMillis ?: -1L)
                put("createdAtMillis", a.createdAtMillis)
                put("updatedAtMillis", a.updatedAtMillis)
            })
        })
        root.put("fosterCases", JSONArray().apply {
            for (c in fosterCases) put(JSONObject().apply {
                put("id", c.id)
                put("animalId", c.animalId)
                put("status", c.status)
                put("intakeDateMillis", c.intakeDateMillis)
                put("outDateMillis", c.outDateMillis ?: -1L)
                put("weightDeclineWarned", c.weightDeclineWarned)
                put("nextVaccineDateMillis", c.nextVaccineDateMillis ?: -1L)
                put("collarColor", c.collarColor ?: "")
                put("notes", c.notes ?: "")
                put("createdAtMillis", c.createdAtMillis)
                put("updatedAtMillis", c.updatedAtMillis)
            })
        })
        root.put("weights", JSONArray().apply {
            for (w in weights) put(JSONObject().apply {
                put("fosterCaseId", w.fosterCaseId)
                put("dateMillis", w.dateMillis)
                put("weightGrams", w.weightGrams.toDouble())
                put("syncId", w.syncId)
            })
        })
        root.put("stools", JSONArray().apply {
            for (s in stools) put(JSONObject().apply {
                put("fosterCaseId", s.fosterCaseId)
                put("dateMillis", s.dateMillis)
                put("level", s.level)
                put("syncId", s.syncId)
            })
        })
        root.put("events", JSONArray().apply {
            for (e in events) put(JSONObject().apply {
                put("fosterCaseId", e.fosterCaseId)
                put("dateMillis", e.dateMillis)
                put("type", e.type)
                put("syncId", e.syncId)
            })
        })
        root.put("treatments", JSONArray().apply {
            for (t in treatments) put(JSONObject().apply {
                put("fosterCaseId", t.fosterCaseId)
                put("treatmentType", t.treatmentType)
                put("scheduledDateMillis", t.scheduledDateMillis)
                put("administeredDateMillis", t.administeredDateMillis ?: -1L)
                put("doseGiven", t.doseGiven ?: "")
                put("notes", t.notes ?: "")
                put("syncId", t.syncId)
            })
        })
        root.put("medications", JSONArray().apply {
            for (m in medications) put(JSONObject().apply {
                put("id", m.id)
                put("fosterCaseId", m.fosterCaseId)
                put("name", m.name)
                put("instructions", m.instructions)
                put("startDateMillis", m.startDateMillis)
                put("endDateMillis", m.endDateMillis ?: -1L)
                put("isActive", m.isActive)
                put("updatedAtMillis", m.updatedAtMillis)
            })
        })
        root.put("messages", JSONArray().apply {
            for (m in messages) put(JSONObject().apply {
                put("id", m.id)
                put("fosterCaseId", m.fosterCaseId)
                put("title", m.title)
                put("content", m.content)
                put("timestamp", m.timestamp)
                put("isRead", m.isRead)
            })
        })
        root.put("completedRecords", JSONArray().apply {
            for (r in completedRecords) put(JSONObject().apply {
                put("id", r.id)
                put("animalId", r.animalId)
                put("fosterCaseId", r.fosterCaseId)
                put("intakeDateMillis", r.intakeDateMillis)
                put("outDateMillis", r.outDateMillis)
                put("daysFostered", r.daysFostered)
                put("finalWeightGrams", r.finalWeightGrams?.toDouble() ?: -1.0)
                put("weightChangeGrams", r.weightChangeGrams?.toDouble() ?: -1.0)
                put("medicalSummary", r.medicalSummary ?: "")
                put("behaviorSummary", r.behaviorSummary ?: "")
                put("placementSummary", r.placementSummary ?: "")
                put("createdAtMillis", r.createdAtMillis)
            })
        })
        root.put("traits", JSONArray().apply {
            for (t in traits) put(JSONObject().apply {
                put("animalId", t.animalId)
                put("fosterCaseId", t.fosterCaseId ?: "")
                put("traitName", t.traitName)
                put("category", t.category)
                put("valence", t.valence)
                put("score", t.score)
                put("assignedAtMillis", t.assignedAtMillis)
                put("syncId", t.syncId)
            })
        })
        return root
    }

    companion object {
        fun fromJson(json: JSONObject): SyncPayload {
            val deviceId = json.getString("deviceId")
            val timestampMillis = json.getLong("timestampMillis")

            val animals = mutableListOf<AnimalEntity>()
            val animalsArr = json.getJSONArray("animals")
            for (i in 0 until animalsArr.length()) {
                val o = animalsArr.getJSONObject(i)
                animals.add(AnimalEntity(
                    id = o.getString("id"),
                    externalId = o.getString("externalId").takeIf { it.isNotEmpty() },
                    name = o.getString("name"),
                    species = o.getString("species"),
                    breed = o.getString("breed"),
                    color = o.getString("color"),
                    sex = o.getString("sex"),
                    litterName = o.getString("litterName").takeIf { it.isNotEmpty() },
                    estimatedBirthdayMillis = o.getLong("estimatedBirthdayMillis").takeIf { it >= 0 },
                    createdAtMillis = o.getLong("createdAtMillis"),
                    updatedAtMillis = o.getLong("updatedAtMillis")
                ))
            }

            val fosterCases = mutableListOf<FosterCaseEntity>()
            val casesArr = json.getJSONArray("fosterCases")
            for (i in 0 until casesArr.length()) {
                val o = casesArr.getJSONObject(i)
                fosterCases.add(FosterCaseEntity(
                    id = o.getString("id"),
                    animalId = o.getString("animalId"),
                    status = o.getString("status"),
                    intakeDateMillis = o.getLong("intakeDateMillis"),
                    outDateMillis = o.getLong("outDateMillis").takeIf { it >= 0 },
                    weightDeclineWarned = o.getBoolean("weightDeclineWarned"),
                    nextVaccineDateMillis = o.getLong("nextVaccineDateMillis").takeIf { it >= 0 },
                    collarColor = o.getString("collarColor").takeIf { it.isNotEmpty() },
                    notes = o.getString("notes").takeIf { it.isNotEmpty() },
                    createdAtMillis = o.getLong("createdAtMillis"),
                    updatedAtMillis = o.getLong("updatedAtMillis")
                ))
            }

            val weights = mutableListOf<CaseWeightEntity>()
            val weightsArr = json.getJSONArray("weights")
            for (i in 0 until weightsArr.length()) {
                val o = weightsArr.getJSONObject(i)
                weights.add(CaseWeightEntity(
                    id = 0,
                    fosterCaseId = o.getString("fosterCaseId"),
                    dateMillis = o.getLong("dateMillis"),
                    weightGrams = o.getDouble("weightGrams").toFloat(),
                    syncId = o.getString("syncId")
                ))
            }

            val stools = mutableListOf<CaseStoolEntity>()
            val stoolsArr = json.getJSONArray("stools")
            for (i in 0 until stoolsArr.length()) {
                val o = stoolsArr.getJSONObject(i)
                stools.add(CaseStoolEntity(
                    id = 0,
                    fosterCaseId = o.getString("fosterCaseId"),
                    dateMillis = o.getLong("dateMillis"),
                    level = o.getInt("level"),
                    syncId = o.getString("syncId")
                ))
            }

            val events = mutableListOf<CaseEventEntity>()
            val eventsArr = json.getJSONArray("events")
            for (i in 0 until eventsArr.length()) {
                val o = eventsArr.getJSONObject(i)
                events.add(CaseEventEntity(
                    id = 0,
                    fosterCaseId = o.getString("fosterCaseId"),
                    dateMillis = o.getLong("dateMillis"),
                    type = o.getString("type"),
                    syncId = o.getString("syncId")
                ))
            }

            val treatments = mutableListOf<CaseTreatmentEntity>()
            val treatmentsArr = json.getJSONArray("treatments")
            for (i in 0 until treatmentsArr.length()) {
                val o = treatmentsArr.getJSONObject(i)
                treatments.add(CaseTreatmentEntity(
                    id = 0,
                    fosterCaseId = o.getString("fosterCaseId"),
                    treatmentType = o.getString("treatmentType"),
                    scheduledDateMillis = o.getLong("scheduledDateMillis"),
                    administeredDateMillis = o.getLong("administeredDateMillis").takeIf { it >= 0 },
                    doseGiven = o.getString("doseGiven").takeIf { it.isNotEmpty() },
                    notes = o.getString("notes").takeIf { it.isNotEmpty() },
                    syncId = o.getString("syncId")
                ))
            }

            val medications = mutableListOf<CaseMedicationEntity>()
            val medsArr = json.getJSONArray("medications")
            for (i in 0 until medsArr.length()) {
                val o = medsArr.getJSONObject(i)
                medications.add(CaseMedicationEntity(
                    id = o.getString("id"),
                    fosterCaseId = o.getString("fosterCaseId"),
                    name = o.getString("name"),
                    instructions = o.getString("instructions"),
                    startDateMillis = o.getLong("startDateMillis"),
                    endDateMillis = o.getLong("endDateMillis").takeIf { it >= 0 },
                    isActive = o.getBoolean("isActive"),
                    updatedAtMillis = o.getLong("updatedAtMillis")
                ))
            }

            val messages = mutableListOf<CaseMessageEntity>()
            val msgsArr = json.getJSONArray("messages")
            for (i in 0 until msgsArr.length()) {
                val o = msgsArr.getJSONObject(i)
                messages.add(CaseMessageEntity(
                    id = o.getString("id"),
                    fosterCaseId = o.getString("fosterCaseId"),
                    title = o.getString("title"),
                    content = o.getString("content"),
                    timestamp = o.getLong("timestamp"),
                    isRead = o.getBoolean("isRead")
                ))
            }

            val completedRecords = mutableListOf<CompletedFosterRecordEntity>()
            val recsArr = json.getJSONArray("completedRecords")
            for (i in 0 until recsArr.length()) {
                val o = recsArr.getJSONObject(i)
                completedRecords.add(CompletedFosterRecordEntity(
                    id = o.getString("id"),
                    animalId = o.getString("animalId"),
                    fosterCaseId = o.getString("fosterCaseId"),
                    intakeDateMillis = o.getLong("intakeDateMillis"),
                    outDateMillis = o.getLong("outDateMillis"),
                    daysFostered = o.getInt("daysFostered"),
                    finalWeightGrams = o.getDouble("finalWeightGrams").toFloat().takeIf { it >= 0f },
                    weightChangeGrams = o.getDouble("weightChangeGrams").toFloat().takeIf { it >= 0f },
                    medicalSummary = o.getString("medicalSummary").takeIf { it.isNotEmpty() },
                    behaviorSummary = o.getString("behaviorSummary").takeIf { it.isNotEmpty() },
                    placementSummary = o.getString("placementSummary").takeIf { it.isNotEmpty() },
                    createdAtMillis = o.getLong("createdAtMillis")
                ))
            }

            val traits = mutableListOf<AssignedTraitEntity>()
            val traitsArr = json.getJSONArray("traits")
            for (i in 0 until traitsArr.length()) {
                val o = traitsArr.getJSONObject(i)
                traits.add(AssignedTraitEntity(
                    id = 0,
                    animalId = o.getString("animalId"),
                    fosterCaseId = o.getString("fosterCaseId").takeIf { it.isNotEmpty() },
                    traitName = o.getString("traitName"),
                    category = o.getString("category"),
                    valence = o.getString("valence"),
                    score = o.getInt("score"),
                    assignedAtMillis = o.getLong("assignedAtMillis"),
                    syncId = o.getString("syncId")
                ))
            }

            return SyncPayload(
                deviceId = deviceId,
                timestampMillis = timestampMillis,
                animals = animals,
                fosterCases = fosterCases,
                weights = weights,
                stools = stools,
                events = events,
                treatments = treatments,
                medications = medications,
                messages = messages,
                completedRecords = completedRecords,
                traits = traits
            )
        }
    }
}
