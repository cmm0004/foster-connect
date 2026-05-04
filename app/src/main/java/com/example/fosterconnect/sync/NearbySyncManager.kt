package com.example.fosterconnect.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.fosterconnect.data.db.AppDatabase
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class NearbySyncManager(
    private val context: Context,
    private val db: AppDatabase
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    private var connectedEndpointId: String? = null
    private var receivedBytes: ByteArrayOutputStream? = null
    private var timeoutJob: Job? = null
    @Volatile private var localMergeDone = false
    @Volatile private var remoteMergeDone = false
    private var mergeStats: MergeStats? = null

    private val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    private val pairedDeviceName: String?
        get() = prefs.getString("paired_device_name", null)

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val authToken = info.authenticationDigits
            val remoteDeviceName = info.endpointName

            if (pairedDeviceName != null && pairedDeviceName == remoteDeviceName) {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                _state.value = SyncState.Connected
            } else {
                _state.value = SyncState.Authenticating(endpointId, remoteDeviceName, authToken)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                timeoutJob?.cancel()
                connectedEndpointId = endpointId
                stopDiscoveryAndAdvertising()
                _state.value = SyncState.Transferring
                sendLocalData()
            } else {
                _state.value = SyncState.Error("Connection failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpointId = null
            if (localMergeDone && _state.value !is SyncState.Done) {
                _state.value = SyncState.Done(mergeStats ?: MergeStats())
            } else if (!localMergeDone && _state.value is SyncState.Transferring) {
                _state.value = SyncState.Error("Connection lost during transfer")
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.STREAM -> {
                    scope.launch {
                        try {
                            val stream = payload.asStream()?.asInputStream() ?: return@launch
                            val buffer = ByteArrayOutputStream()
                            stream.use { input ->
                                val buf = ByteArray(8192)
                                var len: Int
                                while (input.read(buf).also { len = it } != -1) {
                                    buffer.write(buf, 0, len)
                                }
                            }
                            handleReceivedData(buffer.toByteArray())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reading payload stream", e)
                            _state.value = SyncState.Error("Failed to read data: ${e.message}")
                        }
                    }
                }
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    if (bytes.contentEquals(SYNC_DONE_SIGNAL)) {
                        remoteMergeDone = true
                        maybeDisconnect()
                    } else {
                        scope.launch { handleReceivedData(bytes) }
                    }
                }
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(deviceId, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    fun startSync() {
        localMergeDone = false
        remoteMergeDone = false
        mergeStats = null
        _state.value = SyncState.Searching
        startAdvertising()
        startDiscovery()
        timeoutJob = scope.launch {
            delay(30_000)
            if (_state.value is SyncState.Searching) {
                stopDiscoveryAndAdvertising()
                _state.value = SyncState.Error("No device found within 30 seconds")
            }
        }
    }

    fun acceptConnection(endpointId: String, remoteDeviceName: String) {
        prefs.edit().putString("paired_device_name", remoteDeviceName).apply()
        connectionsClient.acceptConnection(endpointId, payloadCallback)
        _state.value = SyncState.Connected
    }

    fun rejectConnection(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
        _state.value = SyncState.Error("Connection rejected")
    }

    fun stop() {
        timeoutJob?.cancel()
        stopDiscoveryAndAdvertising()
        connectedEndpointId?.let { connectionsClient.disconnectFromEndpoint(it) }
        connectedEndpointId = null
        _state.value = SyncState.Idle
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()
        connectionsClient.startAdvertising(
            deviceId, SERVICE_ID, connectionLifecycleCallback, options
        )
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
    }

    private fun stopDiscoveryAndAdvertising() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    private fun sendLocalData() {
        scope.launch {
            try {
                val syncDao = db.syncDao()
                val payload = SyncPayload(
                    deviceId = deviceId,
                    timestampMillis = System.currentTimeMillis(),
                    animals = syncDao.getAllAnimals(),
                    fosterCases = syncDao.getAllFosterCases(),
                    weights = syncDao.getAllWeights(),
                    stools = syncDao.getAllStools(),
                    events = syncDao.getAllEvents(),
                    treatments = syncDao.getAllTreatments(),
                    medications = syncDao.getAllMedications(),
                    messages = syncDao.getAllMessages(),
                    completedRecords = syncDao.getAllCompletedRecords(),
                    traits = syncDao.getAllTraits()
                )
                val jsonBytes = payload.toJson().toString().toByteArray(Charsets.UTF_8)
                val stream = ByteArrayInputStream(jsonBytes)
                val nearbyPayload = Payload.fromStream(stream)
                connectedEndpointId?.let { endpointId ->
                    connectionsClient.sendPayload(endpointId, nearbyPayload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending data", e)
                _state.value = SyncState.Error("Failed to send data: ${e.message}")
            }
        }
    }

    private suspend fun handleReceivedData(bytes: ByteArray) {
        try {
            _state.value = SyncState.Merging
            val jsonStr = String(bytes, Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            val remotePayload = SyncPayload.fromJson(json)
            val merger = SyncMerger(db)
            val stats = merger.merge(remotePayload)
            prefs.edit().putLong("last_sync_millis", System.currentTimeMillis()).apply()

            connectedEndpointId?.let { endpointId ->
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(SYNC_DONE_SIGNAL))
            }
            mergeStats = stats
            localMergeDone = true
            maybeDisconnect()

            scope.launch {
                delay(10_000)
                if (_state.value is SyncState.WaitingForPeer) {
                    Log.w(TAG, "Timed out waiting for peer done signal")
                    connectedEndpointId?.let { connectionsClient.disconnectFromEndpoint(it) }
                    connectedEndpointId = null
                    _state.value = SyncState.Done(mergeStats ?: MergeStats())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error merging data", e)
            _state.value = SyncState.Error("Merge failed: ${e.message}")
        }
    }

    private fun maybeDisconnect() {
        if (localMergeDone && remoteMergeDone) {
            connectedEndpointId?.let { connectionsClient.disconnectFromEndpoint(it) }
            connectedEndpointId = null
            _state.value = SyncState.Done(mergeStats ?: MergeStats())
        } else if (localMergeDone) {
            _state.value = SyncState.WaitingForPeer
        }
    }

    companion object {
        private const val TAG = "NearbySyncManager"
        private const val SERVICE_ID = "com.example.fosterconnect.sync"
        private val SYNC_DONE_SIGNAL = "SYNC_DONE".toByteArray(Charsets.UTF_8)
    }
}
