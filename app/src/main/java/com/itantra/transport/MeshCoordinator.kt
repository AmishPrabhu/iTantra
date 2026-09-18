package com.itantra.transport

import android.content.Context
import android.util.Log
import com.itantra.ai.model.Language
import com.itantra.ai.translation.IndicTranslationEngine
import com.itantra.ai.tts.ChunkedTtsEngine
import com.itantra.transport.bluetooth.BluetoothMeshManager
import com.itantra.transport.dtn.DtnMessageQueue
import com.itantra.transport.protocol.PacketProtocol
import com.itantra.transport.wifidirect.WifiDirectMeshManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransportMode {
    WIFI_DIRECT,
    BLUETOOTH_SPP,
    DUAL_AUTO_MESH
}

/**
 * Unified Disaster Mesh Coordinator.
 * Manages Dual-Mesh networking (Wi-Fi Direct + Bluetooth SPP),
 * packet dispatching, offline neural translation, and DTN queue sync.
 */
class MeshCoordinator(
    private val context: Context,
    private val translationEngine: IndicTranslationEngine,
    private val ttsEngine: ChunkedTtsEngine,
    private val dtnQueue: DtnMessageQueue
) {

    private val TAG = "MeshCoordinator"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val localNodeId: String = try {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)?.take(6) ?: "node_${(1000..9999).random()}"
    } catch (e: Exception) {
        "node_${(1000..9999).random()}"
    }

    val localDeviceName: String = try {
        val model = android.os.Build.MODEL ?: "Responder"
        if (model.isNotBlank()) model else "Responder $localNodeId"
    } catch (e: Exception) {
        "Responder $localNodeId"
    }

    data class MeshNodeInfo(
        val nodeId: String,
        val deviceName: String,
        val preferredLanguage: Language,
        val transport: TransportMode,
        val ipAddress: String?,
        val lastSeenMs: Long
    )

    private val activeMeshNodes = java.util.concurrent.ConcurrentHashMap<String, MeshNodeInfo>()
    private val _liveMeshNodes = MutableStateFlow<List<MeshNodeInfo>>(emptyList())
    val liveMeshNodes: StateFlow<List<MeshNodeInfo>> = _liveMeshNodes.asStateFlow()

    private val _connectedPeersCount = MutableStateFlow(1)
    val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val _preferredLanguage = MutableStateFlow(Language.HINDI)
    val preferredLanguage: StateFlow<Language> = _preferredLanguage.asStateFlow()

    private val _activeTransport = MutableStateFlow(TransportMode.DUAL_AUTO_MESH)
    val activeTransport: StateFlow<TransportMode> = _activeTransport.asStateFlow()

    private var bluetoothManager: BluetoothMeshManager? = null
    private var wifiDirectManager: WifiDirectMeshManager? = null
    private var heartbeatJob: Job? = null

    var emergencyAlertListener: ((alertText: String, sourceLang: Language, senderName: String) -> Unit)? = null

    val discoveredWifiDirectDevices: StateFlow<List<android.net.wifi.p2p.WifiP2pDevice>>
        get() = wifiDirectManager?.discoveredDevices ?: MutableStateFlow(emptyList())

    fun getDiscoveredBluetoothDevices(): List<android.bluetooth.BluetoothDevice> {
        return bluetoothManager?.getDiscoveredDevices() ?: emptyList()
    }

    init {
        initTransportManagers()
        startHeartbeatWatchdog()
    }

    private fun initTransportManagers() {
        bluetoothManager = BluetoothMeshManager(context, localNodeId) { rawPacket ->
            handleIncomingPacket(rawPacket, "Bluetooth_Peer")
        }

        wifiDirectManager = WifiDirectMeshManager(context) { rawPacket, senderIp ->
            handleIncomingPacket(rawPacket, senderIp)
        }

        bluetoothManager?.startMesh()
        wifiDirectManager?.startDiscovery()
    }

    fun restartTransports() {
        Log.i(TAG, "Restarting mesh radio transports after permission approval/resume...")
        bluetoothManager?.stop()
        initTransportManagers()
    }

    private fun startHeartbeatWatchdog() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(1500) // 1.5s Heartbeat interval for dynamic discovery
                try {
                    val pingPayload = PacketProtocol.encodeHeartbeatPayload(
                        nodeId = localNodeId,
                        deviceName = localDeviceName,
                        lang = _preferredLanguage.value
                    )
                    val heartbeatPacket = PacketProtocol.encodePacket(
                        type = PacketProtocol.TYPE_HEARTBEAT_PING,
                        sourceLang = _preferredLanguage.value,
                        sequenceId = 0,
                        textPayload = pingPayload
                    )
                    broadcastToMesh(heartbeatPacket)

                    // Prune inactive nodes (silent for > 6 seconds)
                    val now = System.currentTimeMillis()
                    var pruned = false
                    val iterator = activeMeshNodes.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (now - entry.value.lastSeenMs > 6000) {
                            iterator.remove()
                            pruned = true
                        }
                    }
                    if (pruned || _liveMeshNodes.value.size != activeMeshNodes.size) {
                        _liveMeshNodes.value = activeMeshNodes.values.toList()
                        _connectedPeersCount.value = activeMeshNodes.size + 1
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat ping failed: ${e.message}")
                }
            }
        }
    }

    fun setPreferredLanguage(lang: Language) {
        _preferredLanguage.value = lang
        Log.i(TAG, "User Preferred Language set to: ${lang.englishName} (${lang.nativeName})")
    }

    fun setTransportMode(mode: TransportMode) {
        _activeTransport.value = mode
        Log.i(TAG, "Transport Mode switched to: $mode")
    }

    private val sentSequenceIds = java.util.Collections.synchronizedSet(java.util.HashSet<Int>())
    private val receivedSequenceIds = java.util.Collections.synchronizedSet(java.util.HashSet<Int>())

    /**
     * Broadcasts outgoing transcribed speech text to all mesh nodes.
     */
    suspend fun transmitVoiceText(
        spokenText: String,
        spokenLang: Language,
        targetPeerId: String = "ALL"
    ) = withContext(Dispatchers.IO) {
        if (spokenText.isBlank()) return@withContext

        // 1. Enqueue in local DTN queue
        val dtnMsg = dtnQueue.enqueueOutgoingMessage(
            type = PacketProtocol.TYPE_VOICE_TEXT,
            sourceLang = spokenLang,
            originalText = spokenText,
            translatedText = spokenText,
            senderNodeId = "Self"
        )
        sentSequenceIds.add(dtnMsg.sequenceId)

        // 2. Encode into binary packet with structured envelope
        val voicePayload = PacketProtocol.encodeVoicePayload(
            senderNodeId = localNodeId,
            senderDeviceName = localDeviceName,
            targetNodeId = targetPeerId,
            text = spokenText
        )
        val packet = PacketProtocol.encodePacket(
            type = PacketProtocol.TYPE_VOICE_TEXT,
            sourceLang = spokenLang,
            sequenceId = dtnMsg.sequenceId,
            textPayload = voicePayload
        )

        // 3. Dispatch over active transport
        broadcastToMesh(packet)
    }

    /**
     * Broadcasts instant 0ms Emergency Alert to all nodes.
     */
    suspend fun transmitEmergencyAlert(
        alertText: String,
        sourceLang: Language = Language.HINDI
    ) = withContext(Dispatchers.IO) {
        val dtnMsg = dtnQueue.enqueueOutgoingMessage(
            type = PacketProtocol.TYPE_EMERGENCY_ALERT,
            sourceLang = sourceLang,
            originalText = alertText,
            translatedText = alertText,
            senderNodeId = "Self_Emergency"
        )
        sentSequenceIds.add(dtnMsg.sequenceId)

        val alertPayload = PacketProtocol.encodeVoicePayload(
            senderNodeId = localNodeId,
            senderDeviceName = localDeviceName,
            targetNodeId = "ALL",
            text = alertText
        )
        val packet = PacketProtocol.encodePacket(
            type = PacketProtocol.TYPE_EMERGENCY_ALERT,
            sourceLang = sourceLang,
            sequenceId = dtnMsg.sequenceId,
            textPayload = alertPayload
        )

        // 1. Dual-radio broadcast (Wi-Fi UDP + BLE GATT + Insecure RFCOMM)
        broadcastToMesh(packet)

        // 2. Instantaneous connectionless BLE burst for unlinked devices
        bluetoothManager?.broadcastEmergencyBleBurst(packet)
    }

    fun connectToPeer(peerId: String) {
        scope.launch {
            Log.i(TAG, "Initiating hardware connection to peer: $peerId")
            if (peerId.startsWith("bt_")) {
                val address = peerId.removePrefix("bt_")
                bluetoothManager?.getDiscoveredDevices()?.find { it.address.equals(address, ignoreCase = true) }?.let { dev ->
                    bluetoothManager?.connectToDevice(dev)
                }
            } else if (peerId.startsWith("wfd_")) {
                val address = peerId.removePrefix("wfd_")
                wifiDirectManager?.connectToP2pDevice(address)
            } else if (peerId.startsWith("node_")) {
                val node = activeMeshNodes[peerId]
                if (node?.ipAddress != null) {
                    wifiDirectManager?.connectToPeer(node.ipAddress)
                }
            }
        }
    }

    private val receivedPacketKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun broadcastToMesh(packetBytes: ByteArray) {
        // Dual-radio redundant transmission: always dispatch via both Wi-Fi UDP and Bluetooth SPP
        wifiDirectManager?.broadcastPacket(packetBytes)
        bluetoothManager?.broadcastPacket(packetBytes)
    }

    private fun handleIncomingPacket(rawPacket: ByteArray, senderId: String) {
        scope.launch {
            val decoded = PacketProtocol.decodePacket(rawPacket) ?: return@launch

            if (!decoded.isCrcValid) {
                Log.e(TAG, "CRC32 checksum mismatch on packet from $senderId - discarding corrupt data")
                return@launch
            }

            when (decoded.type) {
                PacketProtocol.TYPE_HEARTBEAT_PING -> {
                    val pingInfo = PacketProtocol.parseHeartbeatPayload(decoded.payloadText)
                    if (pingInfo != null) {
                        // Loopback check: ignore our own heartbeat
                        if (pingInfo.nodeId == localNodeId) {
                            return@launch
                        }
                        val transport = if (senderId.contains("Bluetooth", ignoreCase = true)) TransportMode.BLUETOOTH_SPP else TransportMode.WIFI_DIRECT
                        val node = MeshNodeInfo(
                            nodeId = pingInfo.nodeId,
                            deviceName = pingInfo.deviceName,
                            preferredLanguage = pingInfo.language,
                            transport = transport,
                            ipAddress = if (senderId != "WifiDirect_Peer" && senderId != "Bluetooth_Peer") senderId else null,
                            lastSeenMs = System.currentTimeMillis()
                        )
                        activeMeshNodes[pingInfo.nodeId] = node
                        _liveMeshNodes.value = activeMeshNodes.values.toList()
                        _connectedPeersCount.value = activeMeshNodes.size + 1
                        Log.d(TAG, "Mesh peer online: ${node.deviceName} (${node.preferredLanguage.englishName}) on ${node.transport} from $senderId")
                    }
                }
                PacketProtocol.TYPE_ACK -> {
                    dtnQueue.markAcknowledged(decoded.sequenceId)
                }
                PacketProtocol.TYPE_EMERGENCY_ALERT -> {
                    val alertPayload = PacketProtocol.parseVoicePayload(decoded.payloadText)
                    if (alertPayload.senderNodeId == localNodeId) {
                        return@launch
                    }

                    // Multi-radio deduplication across BLE burst, GATT, RFCOMM, UDP
                    val dedupeKey = "ALERT_${alertPayload.senderNodeId}_${decoded.sequenceId}"
                    if (!receivedPacketKeys.add(dedupeKey)) {
                        Log.d(TAG, "Ignoring duplicate emergency alert $dedupeKey")
                        return@launch
                    }

                    Log.i(TAG, "🚨 CRITICAL EMERGENCY ALERT RECEIVED from ${alertPayload.senderDeviceName}: ${alertPayload.text}")

                    // Translate into receiver's preferred language
                    val translated = translationEngine.translateText(
                        alertPayload.text,
                        decoded.sourceLanguage,
                        _preferredLanguage.value
                    )
                    dtnQueue.enqueueIncomingMessage(
                        decoded.sequenceId,
                        decoded.type,
                        decoded.sourceLanguage.id,
                        alertPayload.text,
                        translated,
                        alertPayload.senderDeviceName
                    )

                    // 1. Notify background service to show high-priority notification and wake device
                    emergencyAlertListener?.invoke(alertPayload.text, decoded.sourceLanguage, alertPayload.senderDeviceName)

                    // 2. Play 3x siren tone + 3x translated voice through speaker!
                    scope.launch {
                        for (iteration in 1..3) {
                            try {
                                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                                toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 550)
                                delay(600)
                                toneGen.release()
                            } catch (ignored: Exception) {}

                            ttsEngine.synthesizeAndPlay(translated, _preferredLanguage.value)
                            if (iteration < 3) {
                                delay(800) // Pause between repeats
                            }
                        }
                    }
                }
                PacketProtocol.TYPE_VOICE_TEXT -> {
                    val voicePayload = PacketProtocol.parseVoicePayload(decoded.payloadText)
                    // Loopback check: ignore our own transmissions
                    if (voicePayload.senderNodeId == localNodeId) {
                        return@launch
                    }

                    // Accept all incoming disaster mesh packets (only drop self-loopback)
                    Log.i(TAG, "Accepting incoming voice packet from ${voicePayload.senderDeviceName} (Target: ${voicePayload.targetNodeId})")

                    // Multi-radio & retransmission deduplication
                    val dedupeKey = "VOICE_${voicePayload.senderNodeId}_${decoded.sequenceId}"
                    if (!receivedPacketKeys.add(dedupeKey)) {
                        Log.d(TAG, "Ignoring duplicate packet $dedupeKey")
                        return@launch
                    }

                    // Update sender node presence
                    if (voicePayload.senderNodeId.isNotBlank() && voicePayload.senderNodeId != "Peer") {
                        val transport = if (senderId.contains("Bluetooth", ignoreCase = true)) TransportMode.BLUETOOTH_SPP else TransportMode.WIFI_DIRECT
                        val node = MeshNodeInfo(
                            nodeId = voicePayload.senderNodeId,
                            deviceName = voicePayload.senderDeviceName,
                            preferredLanguage = decoded.sourceLanguage,
                            transport = transport,
                            ipAddress = if (senderId != "WifiDirect_Peer" && senderId != "Bluetooth_Peer") senderId else null,
                            lastSeenMs = System.currentTimeMillis()
                        )
                        activeMeshNodes[voicePayload.senderNodeId] = node
                        _liveMeshNodes.value = activeMeshNodes.values.toList()
                        _connectedPeersCount.value = activeMeshNodes.size + 1
                    }

                    Log.i(TAG, "Received Voice Text: '${voicePayload.text}' from ${voicePayload.senderDeviceName} (${decoded.sourceLanguage.englishName})")

                    // 1. Translate locally on receiver's phone into receiver's preferred language
                    val translated = translationEngine.translateText(
                        voicePayload.text,
                        decoded.sourceLanguage,
                        _preferredLanguage.value
                    )

                    // 2. Persist in DTN history with real sender device name
                    dtnQueue.enqueueIncomingMessage(
                        decoded.sequenceId,
                        decoded.type,
                        decoded.sourceLanguage.id,
                        voicePayload.text,
                        translated,
                        voicePayload.senderDeviceName
                    )

                    // 3. Send ACK back to sender
                    val ackPacket = PacketProtocol.createAckPacket(decoded.sequenceId)
                    broadcastToMesh(ackPacket)

                    // 4. Play audio through speaker in receiver's preferred language
                    ttsEngine.synthesizeAndPlay(translated, _preferredLanguage.value)
                }
            }
        }
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        bluetoothManager?.stop()
        wifiDirectManager?.stop()
        ttsEngine.shutdown()
        scope.cancel()
    }
}
