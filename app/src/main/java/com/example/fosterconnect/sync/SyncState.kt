package com.example.fosterconnect.sync

sealed class SyncState {
    object Idle : SyncState()
    object Searching : SyncState()
    data class Authenticating(
        val endpointId: String,
        val deviceName: String,
        val authToken: String
    ) : SyncState()
    object Connected : SyncState()
    object Transferring : SyncState()
    object Merging : SyncState()
    object WaitingForPeer : SyncState()
    data class Done(val stats: MergeStats) : SyncState()
    data class Error(val message: String) : SyncState()
}

data class MergeStats(
    val animalsAdded: Int = 0,
    val animalsUpdated: Int = 0,
    val casesAdded: Int = 0,
    val casesUpdated: Int = 0,
    val weightsAdded: Int = 0,
    val stoolsAdded: Int = 0,
    val eventsAdded: Int = 0,
    val treatmentsAdded: Int = 0,
    val treatmentsUpdated: Int = 0,
    val medicationsAdded: Int = 0,
    val medicationsUpdated: Int = 0,
    val messagesAdded: Int = 0,
    val completedRecordsAdded: Int = 0,
    val traitsAdded: Int = 0
) {
    val totalAdded: Int get() = animalsAdded + casesAdded + weightsAdded + stoolsAdded +
            eventsAdded + treatmentsAdded + medicationsAdded + messagesAdded +
            completedRecordsAdded + traitsAdded
    val totalUpdated: Int get() = animalsUpdated + casesUpdated + treatmentsUpdated + medicationsUpdated
}
