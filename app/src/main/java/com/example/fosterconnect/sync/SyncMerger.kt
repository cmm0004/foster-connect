package com.example.fosterconnect.sync

import androidx.room.withTransaction
import com.example.fosterconnect.data.db.AppDatabase

class SyncMerger(private val db: AppDatabase) {

    suspend fun merge(remote: SyncPayload): MergeStats = db.withTransaction {
        val syncDao = db.syncDao()
        var stats = MergeStats()

        // 1. Animals (String PK, LWW by updatedAtMillis)
        val localAnimals = syncDao.getAllAnimals().associateBy { it.id }
        for (remoteAnimal in remote.animals) {
            val local = localAnimals[remoteAnimal.id]
            if (local == null) {
                syncDao.upsertAnimal(remoteAnimal)
                stats = stats.copy(animalsAdded = stats.animalsAdded + 1)
            } else if (remoteAnimal.updatedAtMillis > local.updatedAtMillis) {
                syncDao.upsertAnimal(remoteAnimal)
                stats = stats.copy(animalsUpdated = stats.animalsUpdated + 1)
            }
        }

        // 2. FosterCases (String PK, LWW by updatedAtMillis)
        val localCases = syncDao.getAllFosterCases().associateBy { it.id }
        for (remoteCase in remote.fosterCases) {
            val local = localCases[remoteCase.id]
            if (local == null) {
                syncDao.upsertFosterCase(remoteCase)
                stats = stats.copy(casesAdded = stats.casesAdded + 1)
            } else if (remoteCase.updatedAtMillis > local.updatedAtMillis) {
                syncDao.upsertFosterCase(remoteCase)
                stats = stats.copy(casesUpdated = stats.casesUpdated + 1)
            }
        }

        // 3. Weights (append-only, union by syncId)
        val localWeightSyncIds = syncDao.getAllWeightSyncIds().toHashSet()
        for (w in remote.weights) {
            if (w.syncId.isNotEmpty() && w.syncId !in localWeightSyncIds) {
                syncDao.insertWeightIfNew(w)
                stats = stats.copy(weightsAdded = stats.weightsAdded + 1)
            }
        }

        // 4. Stools (append-only, union by syncId)
        val localStoolSyncIds = syncDao.getAllStoolSyncIds().toHashSet()
        for (s in remote.stools) {
            if (s.syncId.isNotEmpty() && s.syncId !in localStoolSyncIds) {
                syncDao.insertStoolIfNew(s)
                stats = stats.copy(stoolsAdded = stats.stoolsAdded + 1)
            }
        }

        // 5. Events (append-only, union by syncId)
        val localEventSyncIds = syncDao.getAllEventSyncIds().toHashSet()
        for (e in remote.events) {
            if (e.syncId.isNotEmpty() && e.syncId !in localEventSyncIds) {
                syncDao.insertEventIfNew(e)
                stats = stats.copy(eventsAdded = stats.eventsAdded + 1)
            }
        }

        // 6. Treatments (append-only + mutable administered state)
        val localTreatmentSyncIds = syncDao.getAllTreatmentSyncIds().toHashSet()
        for (t in remote.treatments) {
            if (t.syncId.isNotEmpty() && t.syncId !in localTreatmentSyncIds) {
                syncDao.insertTreatmentIfNew(t)
                stats = stats.copy(treatmentsAdded = stats.treatmentsAdded + 1)
            } else if (t.syncId.isNotEmpty() && t.administeredDateMillis != null) {
                val localTreatment = syncDao.getTreatmentBySyncId(t.syncId)
                if (localTreatment != null && localTreatment.administeredDateMillis == null) {
                    syncDao.updateTreatmentAdministered(
                        localTreatment.id, t.administeredDateMillis, t.doseGiven
                    )
                    stats = stats.copy(treatmentsUpdated = stats.treatmentsUpdated + 1)
                }
            }
        }

        // 7. Medications (String PK, stopped-wins + LWW)
        val localMeds = syncDao.getAllMedications().associateBy { it.id }
        for (remoteMed in remote.medications) {
            val local = localMeds[remoteMed.id]
            if (local == null) {
                syncDao.upsertMedication(remoteMed)
                stats = stats.copy(medicationsAdded = stats.medicationsAdded + 1)
            } else {
                val shouldUpdate = when {
                    !remoteMed.isActive && local.isActive -> true
                    remoteMed.updatedAtMillis > local.updatedAtMillis -> true
                    else -> false
                }
                if (shouldUpdate) {
                    syncDao.upsertMedication(remoteMed)
                    stats = stats.copy(medicationsUpdated = stats.medicationsUpdated + 1)
                }
            }
        }

        // 8. Messages (String PK, insert-ignore, isRead true wins)
        val localMessages = syncDao.getAllMessages().associateBy { it.id }
        for (remoteMsg in remote.messages) {
            val local = localMessages[remoteMsg.id]
            if (local == null) {
                syncDao.insertMessageIgnore(remoteMsg)
                stats = stats.copy(messagesAdded = stats.messagesAdded + 1)
            } else if (remoteMsg.isRead && !local.isRead) {
                syncDao.markMessageReadById(remoteMsg.id)
            }
        }

        // 9. CompletedRecords (String PK, upsert)
        val localRecords = syncDao.getAllCompletedRecords().associateBy { it.id }
        for (r in remote.completedRecords) {
            if (r.id !in localRecords) {
                syncDao.upsertCompletedRecord(r)
                stats = stats.copy(completedRecordsAdded = stats.completedRecordsAdded + 1)
            }
        }

        // 10. Traits (composite unique key, insert-ignore)
        for (t in remote.traits) {
            val result = syncDao.insertTraitIgnore(t)
            if (result != -1L) {
                stats = stats.copy(traitsAdded = stats.traitsAdded + 1)
            }
        }

        stats
    }
}
